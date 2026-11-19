// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "subtitletranslator.h"
#include "settings.h"

#include <QBuffer>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLoggingCategory>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QThread>
#include <QThreadPool>
#include <QTimer>
#include <QHash>
#include <QUrl>

#include <algorithm>
#include <memory>

extern "C" {
#include <libavutil/hwcontext.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/pixdesc.h>
}

Q_LOGGING_CATEGORY(chiakiSubtitles, "chiaki.subtitles");

namespace {

constexpr qint64 kGrabIntervalMs = 120;    // about 8 looks per second
constexpr qint64 kMinSendGapMs = 250;      // never more than 4 requests per second
constexpr int kMaxInFlight = 2;
constexpr int kBackupAfterMs = 900;       // no answer by then: send a backup copy of the request
constexpr int kKeepAliveMs = 20000;       // keep the connection to Claude open between lines            // a new line doesn't wait for the previous answer
constexpr int kCell = 2;                   // change-detection grid: 2x2 pixel cells (fine enough to tell two sentences apart)
constexpr int kMinInkCells = 120;          // less than this = no text in the area
constexpr double kStableIoU = 0.70;        // same text as the previous look
constexpr double kNewIoU = 0.55;           // different from the last line sent
constexpr int kMaxImageWidth = 1568;       // larger images are resized by the API anyway
constexpr int kRecentLines = 4;

// Subtitle area: bottom 30% of the picture, middle 68% of its width
// (leaves out the minimap and corner HUD that many games draw at the sides)
constexpr double kAreaLeft = 0.16, kAreaRight = 0.84, kAreaTop = 0.70;

const char *kApiUrl = "https://api.anthropic.com/v1/messages";
const char *kApiVersion = "2026-10-21";

struct Price { const char *prefix; double in_per_mtok; double out_per_mtok; };
const Price kPrices[] = {
    { "claude-haiku-5-5", 0.10, 0.50 },
    { "claude-sonnet-5-5", 2.00, 10.00 },
    { "claude-opus-5-5", 4.00, 20.00 },
};

QString languageName(const QString &code)
{
    return code == QLatin1String("ar") ? QStringLiteral("Arabic") : QStringLiteral("Kurdish Sorani");
}

QString modelLabel(const QString &model)
{
    if (model.startsWith(QLatin1String("claude-haiku")))
        return QStringLiteral("Claude Haiku 5.5");
    if (model.startsWith(QLatin1String("claude-sonnet")))
        return QStringLiteral("Claude Sonnet 5.5");
    if (model.startsWith(QLatin1String("claude-opus")))
        return QStringLiteral("Claude Opus 5.5");
    return model;
}

QString systemPrompt(const QString &language)
{
    QString lang_rules;
    if (language == QLatin1String("ar")) {
        lang_rules = QStringLiteral(
            "- Write clear Modern Standard Arabic with simple, everyday vocabulary and no diacritics.\n"
            "- Write names of people and places in Arabic script as they are pronounced.\n");
    } else {
        lang_rules = QStringLiteral(
            "- Write Central Kurdish (Sorani) in the Kurdish Arabic-based alphabet, using its own letters "
            "(ە ێ ۆ ڕ ڵ ڤ گ چ پ ژ) and spelling. Not Kurmanji, not Latin script, and use common Sorani words "
            "rather than Persian or Arabic ones where a Sorani word exists.\n"
            "- Write names of people and places in Sorani script as they are pronounced "
            "(for example Franklin → فرانکلین, Los Santos → لۆس سانتۆس).\n");
    }

    return QStringLiteral(
        "You read subtitles from video game pictures and translate them into %1 for the player.\n\n"
        "The image is a greyscale cut-out around text near the bottom of one frame of a PlayStation game. It may contain a subtitle "
        "(spoken dialogue, often starting with the speaker's name and a colon) and/or an on-screen mission or "
        "objective text. It may also contain parts of the game world, the HUD, a minimap, button prompts, money, "
        "ammo or score counters: those are not subtitles.\n\n"
        "Answer in plain text, nothing else:\n"
        "Line 1: the subtitle or objective text translated into %1.\n"
        "Line 2: the same text in the original language exactly as written, joined into one line.\n"
        "If there is no readable subtitle or objective text, or it is too cut off to understand, answer only: NONE\n\n"
        "Rules:\n"
        "- Leave out HUD numbers, button prompts and text that belongs to the game world (signs, posters, number plates).\n"
        "- Leave out a speaker name at the start of the line (for example \"FRANKLIN:\").\n"
        "- Use a natural spoken style the player understands at a glance, about as short as the original. Keep the "
        "meaning, tone and attitude of slang and jokes instead of translating word by word. Swearing may stay, but "
        "don't make it stronger.\n"
        "%2")
        .arg(languageName(language), lang_rules);
}


// Haiku 5.5 may run without thinking (fastest). Sonnet 5.5 and Opus 5.5 reject that,
// so they keep adaptive thinking and use low effort instead.
void addSpeedSettings(QJsonObject &body, const QString &model, const QJsonObject &format = {})
{
    QJsonObject output_config{ { "effort", "low" } };
    if (!format.isEmpty())
        output_config.insert("format", format);
    body.insert("output_config", output_config);
    if (model.startsWith(QLatin1String("claude-haiku")))
        body.insert("thinking", QJsonObject{ { "type", "disabled" } });
}

double iou(const std::vector<quint8> &a, const std::vector<quint8> &b)
{
    if (a.size() != b.size() || a.empty())
        return 0.0;
    int inter = 0, uni = 0;
    for (size_t i = 0; i < a.size(); i++) {
        inter += a[i] & b[i];
        uni += a[i] | b[i];
    }
    return uni ? double(inter) / uni : 1.0;
}

} // namespace

SubtitleTranslator::SubtitleTranslator(QObject *parent)
    : QObject(parent)
    , network(new QNetworkAccessManager(this))
{
    clock.start();
    // Keep the secure connection to Claude open between lines, so a line after a quiet moment
    // doesn't wait for a new connection
    auto *keep_alive = new QTimer(this);
    keep_alive->setInterval(kKeepAliveMs);
    connect(keep_alive, &QTimer::timeout, this, [this]() {
        if (is_active)
            network->connectToHostEncrypted(QStringLiteral("api.anthropic.com"));
    });
    keep_alive->start();
}

SubtitleTranslator::~SubtitleTranslator()
{
    enabled_flag.storeRelease(0);
    // Let a running look at a frame finish before this object goes away
    for (int i = 0; i < 200 && worker_busy.loadAcquire(); i++)
        QThread::msleep(5);
}

SubtitleTranslator::Config SubtitleTranslator::config() const
{
    QMutexLocker lock(&config_mutex);
    return cfg;
}

void SubtitleTranslator::refresh(bool session_running)
{
    if (!settings)
        return;

    Config next;
    next.enabled = settings->GetSubtitlesEnabled();
    next.api_key = settings->GetClaudeApiKey().trimmed();
    next.model = settings->GetSubtitlesModel();
    next.language = settings->GetSubtitlesLanguage();
    next.instant = settings->GetSubtitlesInstant();
    {
        QMutexLocker lock(&config_mutex);
        cfg = next;
    }

    const bool now_active = next.enabled && session_running && !next.api_key.isEmpty();
    enabled_flag.storeRelease(now_active ? 1 : 0);
    if (now_active != is_active) {
        is_active = now_active;
        // Open the secure connection now, so the first subtitle doesn't wait for it
        if (is_active)
            network->connectToHostEncrypted(QStringLiteral("api.anthropic.com"));
        if (!is_active) {
            // Stream ended or translation turned off: start fresh next time
            worker_reset.storeRelease(1);
            pending_image.clear();
            recent_lines.clear();
            shown_seq = next_seq;   // answers still on the way for the old stream are dropped
            clearSubtitle();
            setLiveBox(QRectF());
        }
        emit activeChanged();
    }

    const QString missing_key = tr("Add your Claude API key in Settings → Audio/Wifi → Translate Subtitles");
    if (next.enabled && session_running && next.api_key.isEmpty())
        setStatus(missing_key, true);
    else if (status_error && (!next.enabled || status_text == missing_key))
        setStatus(QString(), false);
}

bool SubtitleTranslator::wantsFrame()
{
    if (!enabled_flag.loadAcquire() || worker_busy.loadAcquire())
        return false;
    return clock.elapsed() - last_grab_ms.loadRelaxed() >= kGrabIntervalMs;
}

void SubtitleTranslator::submitFrame(AVFrame *frame)
{
    if (!frame)
        return;
    if (!worker_busy.testAndSetAcquire(0, 1)) {
        av_frame_free(&frame);
        return;
    }
    last_grab_ms.storeRelaxed(clock.elapsed());
    QThreadPool::globalInstance()->start([this, frame]() mutable {
        processFrame(frame);
        av_frame_free(&frame);
        worker_busy.storeRelease(0);
    });
}

void SubtitleTranslator::processFrame(AVFrame *frame)
{
    if (worker_reset.testAndSetAcquire(1, 0)) {
        prev_mask.clear();
        sent_mask.clear();
        empty_samples = 0;
        last_live_box = QRectF();
    }

    // Hardware-decoded frames live on the GPU: copy this one frame to memory (about 3 times a second)
    AVFrame *sw = frame;
    AVFrame *downloaded = nullptr;
    if (frame->hw_frames_ctx) {
        downloaded = av_frame_alloc();
        if (!downloaded || av_hwframe_transfer_data(downloaded, frame, 0) < 0) {
            av_frame_free(&downloaded);
            return;
        }
        sw = downloaded;
    }

    const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(sw->format));
    if (!desc || desc->nb_components < 1 || (desc->flags & (AV_PIX_FMT_FLAG_HWACCEL | AV_PIX_FMT_FLAG_RGB))) {
        av_frame_free(&downloaded);
        return;
    }

    const AVComponentDescriptor &luma = desc->comp[0];
    const int width = std::min(frame->width, sw->width);
    const int height = std::min(frame->height, sw->height);
    const int x0 = int(width * kAreaLeft);
    const int x1 = int(width * kAreaRight);
    const int y0 = int(height * kAreaTop);
    const int cw = x1 - x0;
    const int ch = height - y0;
    if (cw < 64 || ch < 32) {
        av_frame_free(&downloaded);
        return;
    }

    // Brightness of the subtitle area as an 8-bit greyscale picture (full range)
    QImage area(cw, ch, QImage::Format_Grayscale8);
    const bool full_range = sw->color_range == AVCOL_RANGE_JPEG;
    const int depth_shift = std::max(0, luma.depth - 8);
    for (int y = 0; y < ch; y++) {
        const uint8_t *src = sw->data[luma.plane] + qint64(y0 + y) * sw->linesize[luma.plane] + luma.offset;
        uchar *dst = area.scanLine(y);
        for (int x = 0; x < cw; x++) {
            const uint8_t *p = src + qint64(x0 + x) * luma.step;
            int v = luma.depth > 8 ? (AV_RN16(p) >> luma.shift) & ((1 << luma.depth) - 1) : (p[0] >> luma.shift);
            v >>= depth_shift;
            if (!full_range)
                v = (v - 16) * 255 / 219;
            dst[x] = uchar(std::clamp(v, 0, 255));
        }
    }
    av_frame_free(&downloaded);

    // Text = bright pixels next to a dark outline or shadow. Mark 4x4 cells that contain such pixels.
    const int mw = (cw + kCell - 1) / kCell;
    const int mh = (ch + kCell - 1) / kCell;
    std::vector<quint8> mask(size_t(mw) * mh, 0);
    int ink = 0;
    for (int y = 0; y < ch; y++) {
        const uchar *row = area.constScanLine(y);
        for (int x = 0; x < cw; x++) {
            if (row[x] < 190)
                continue;
            quint8 &cell = mask[size_t(y / kCell) * mw + x / kCell];
            if (cell)
                continue;
            bool dark = false;
            for (int d = 1; d <= 3 && !dark; d++) {
                if ((x - d >= 0 && row[x - d] <= 80) || (x + d < cw && row[x + d] <= 80))
                    dark = true;
                else if ((y - d >= 0 && area.constScanLine(y - d)[x] <= 80) || (y + d < ch && area.constScanLine(y + d)[x] <= 80))
                    dark = true;
            }
            if (dark) {
                cell = 1;
                ink++;
            }
        }
    }

    if (ink < kMinInkCells) {
        // The game's subtitle went away; after two empty looks the same line may be sent again later
        if (++empty_samples == 2) {
            sent_mask.clear();
            last_live_box = QRectF();
            QMetaObject::invokeMethod(this, [this]() { setLiveBox(QRectF()); emit sourceCleared(); }, Qt::QueuedConnection);
        }
        prev_mask = std::move(mask);
        return;
    }
    empty_samples = 0;

    // Where the text is, as fractions of the whole frame (so the screen can cover exactly the game's line)
    int min_cx = mw, max_cx = -1, min_cy = mh, max_cy = -1;
    for (int cy = 0; cy < mh; cy++) {
        for (int cx = 0; cx < mw; cx++) {
            if (!mask[size_t(cy) * mw + cx])
                continue;
            min_cx = std::min(min_cx, cx);
            max_cx = std::max(max_cx, cx);
            min_cy = std::min(min_cy, cy);
            max_cy = std::max(max_cy, cy);
        }
    }
    const QRectF text_box(double(x0 + min_cx * kCell) / width,
                          double(y0 + min_cy * kCell) / height,
                          double((max_cx - min_cx + 1) * kCell) / width,
                          double((max_cy - min_cy + 1) * kCell) / height);
    const auto moved = [](const QRectF &a, const QRectF &b) {
        return a.isNull() != b.isNull()
            || qAbs(a.left() - b.left()) > 0.01 || qAbs(a.top() - b.top()) > 0.01
            || qAbs(a.right() - b.right()) > 0.01 || qAbs(a.bottom() - b.bottom()) > 0.01;
    };
    if (moved(text_box, last_live_box)) {
        last_live_box = text_box;
        QMetaObject::invokeMethod(this, [this, text_box]() { setLiveBox(text_box); }, Qt::QueuedConnection);
    }

    const double iou_prev = iou(mask, prev_mask);
    const double iou_sent = iou(mask, sent_mask);
    const bool stable = iou_prev >= kStableIoU;   // text stopped changing (typewriter effects, fades)
    const bool is_new = iou_sent < kNewIoU;       // not the line we already sent
    const qint64 now = clock.elapsed();
    const bool send = stable && is_new && now - last_send_ms >= kMinSendGapMs;
    prev_mask = mask;
    if (!send)
        return;

    qCInfo(chiakiSubtitles).nospace() << "new line seen: ink " << ink << ", same as previous look "
                                      << int(iou_prev * 100) << "%, same as last sent " << int(iou_sent * 100) << "%";
    sent_mask = std::move(mask);
    last_send_ms = now;

    // Send only the text and a small margin around it: fewer image tokens = faster answer and lower cost
    // Wide side margin: coloured words (names, highlights) are darker and may not count as "text" above
    const int pad_x = 140, pad_y = 18;
    const int bx0 = std::max(0, min_cx * kCell - pad_x);
    const int by0 = std::max(0, min_cy * kCell - pad_y);
    const int bx1 = std::min(cw, (max_cx + 1) * kCell + pad_x);
    const int by1 = std::min(ch, (max_cy + 1) * kCell + pad_y);
    QImage image = area.copy(bx0, by0, std::max(1, bx1 - bx0), std::max(1, by1 - by0));
    if (image.width() > kMaxImageWidth)
        image = image.scaledToWidth(kMaxImageWidth, Qt::SmoothTransformation);

    QByteArray data;
    QString media_type = QStringLiteral("image/jpeg");
    {
        QBuffer buffer(&data);
        buffer.open(QIODevice::WriteOnly);
        if (!image.save(&buffer, "JPEG", 85)) {
            data.clear();
            buffer.seek(0);
            media_type = QStringLiteral("image/png");
            if (!image.save(&buffer, "PNG"))
                return;
        }
    }

    const qint64 seen_ms = now;
    QMetaObject::invokeMethod(this, [this, data, media_type, text_box, seen_ms]() { sendImage(data, media_type, text_box, seen_ms); }, Qt::QueuedConnection);
}

void SubtitleTranslator::sendImage(const QByteArray &image, const QString &media_type, const QRectF &box, qint64 seen_ms)
{
    if (!is_active)
        return;
    if (in_flight >= kMaxInFlight) {
        // Keep only the newest line; an older one waiting would be out of date by now
        pending_image = image;
        pending_media_type = media_type;
        pending_box = box;
        pending_seen_ms = seen_ms;
        return;
    }
    startRequest(image, media_type, box, seen_ms);
}

void SubtitleTranslator::setLiveBox(const QRectF &box)
{
    if (live_box == box)
        return;
    live_box = box;
    emit liveBoxChanged();
}

QByteArray SubtitleTranslator::buildRequest(const Config &c, const QByteArray &image, const QString &media_type) const
{
    QJsonArray content;
    if (!recent_lines.isEmpty()) {
        content.append(QJsonObject{
            { "type", "text" },
            { "text", QStringLiteral("Earlier lines in this scene (use the same names and wording):\n- %1")
                          .arg(recent_lines.join(QStringLiteral("\n- "))) },
        });
    }
    content.append(QJsonObject{
        { "type", "image" },
        { "source", QJsonObject{
            { "type", "base64" },
            { "media_type", media_type },
            { "data", QString::fromLatin1(image.toBase64()) },
        } },
    });
    content.append(QJsonObject{ { "type", "text" }, { "text", "Read and translate the subtitle in this image." } });

    QJsonObject body{
        { "model", c.model },
        { "max_tokens", 1024 },
        { "stream", true },
        { "system", systemPrompt(c.language) },
        { "messages", QJsonArray{ QJsonObject{ { "role", "user" }, { "content", content } } } },
    };
    // Plain text, not a fixed JSON format: measured about 0.5 s faster to the first word
    addSpeedSettings(body, c.model);
    return QJsonDocument(body).toJson(QJsonDocument::Compact);
}

static QNetworkRequest apiRequest(const QString &api_key)
{
    QNetworkRequest request{ QUrl(QString::fromLatin1(kApiUrl)) };
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("x-api-key", api_key.toUtf8());
    request.setRawHeader("anthropic-version", kApiVersion);
    request.setTransferTimeout(20000);
    return request;
}

namespace {

} // namespace

struct SubtitleTranslator::Request {
    int seq = 0;
    QRectF box;
    qint64 seen_ms = 0;
    qint64 sent_ms = 0;
    QByteArray body;                 // kept to send a backup copy
    QString api_key;
    QList<QNetworkReply *> replies;  // the first try and maybe a backup copy
    QNetworkReply *winner = nullptr; // the copy that started answering first; the other is cancelled
    int open = 0;
    QHash<QNetworkReply *, QByteArray> buffers;      // unparsed stream data
    QHash<QNetworkReply *, QByteArray> error_bodies; // body of a failed copy
    QString answer;                  // the answer as written so far
    bool no_subtitle = false;
    bool line_complete = false;
    bool done = false;
    QString stop_reason;
    QString stream_error;
    double in_tokens = 0, out_tokens = 0;
};

void SubtitleTranslator::startRequest(const QByteArray &image, const QString &media_type, const QRectF &box, qint64 seen_ms)
{
    const Config c = config();
    if (c.api_key.isEmpty())
        return;
    auto req = std::make_shared<Request>();
    req->seq = ++next_seq;
    req->box = box;
    req->seen_ms = seen_ms;
    req->sent_ms = clock.elapsed();
    req->body = buildRequest(c, image, media_type);
    req->api_key = c.api_key;
    in_flight++;
    sendCopy(req);

    // Claude sometimes takes 2-3 s to start. If it hasn't started after a short wait, send the same
    // request again and use whichever answers first (costs a little more, only when Claude is slow).
    std::weak_ptr<Request> weak = req;
    QTimer::singleShot(kBackupAfterMs, this, [this, weak]() {
        auto r = weak.lock();
        if (!r || r->winner || r->done || r->open <= 0 || !is_active || r->replies.size() > 1)
            return;
        qCInfo(chiakiSubtitles) << "line" << r->seq << ": Claude is slow, sending a backup request";
        sendCopy(r);
    });
}

void SubtitleTranslator::sendCopy(const std::shared_ptr<Request> &req)
{
    QNetworkReply *reply = network->post(apiRequest(req->api_key), req->body);
    req->replies.append(reply);
    req->open++;
    // The answer is streamed, so the translation can be shown while it is written
    connect(reply, &QNetworkReply::readyRead, this, [this, reply, req]() { readStream(reply, req); });
    connect(reply, &QNetworkReply::finished, this, [this, reply, req]() { finishStream(reply, req); });
}

void SubtitleTranslator::readStream(QNetworkReply *reply, const std::shared_ptr<Request> &req)
{
    const int http = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (http != 200) {
        req->error_bodies[reply] += reply->readAll();
        return;
    }
    if (req->winner && reply != req->winner) {
        reply->readAll();
        return;
    }
    QByteArray &buffer = req->buffers[reply];
    buffer += reply->readAll();
    int newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
        const QByteArray line = buffer.left(newline).trimmed();
        buffer.remove(0, newline + 1);
        if (!line.startsWith("data:"))
            continue;
        const QJsonObject event = QJsonDocument::fromJson(line.mid(5).trimmed()).object();
        const QString type = event.value("type").toString();
        if (type == QLatin1String("message_start")) {
            req->in_tokens = event.value("message").toObject().value("usage").toObject().value("input_tokens").toDouble();
        } else if (type == QLatin1String("content_block_delta")) {
            const QJsonObject delta = event.value("delta").toObject();
            if (delta.value("type").toString() != QLatin1String("text_delta"))
                continue;
            if (!req->winner) {
                req->winner = reply;
                // The other copy is no longer needed
                for (QNetworkReply *other : std::as_const(req->replies)) {
                    if (other != reply && other->isRunning())
                        other->abort();
                }
            }
            req->answer += delta.value("text").toString();
            updateLine(req, false);
        } else if (type == QLatin1String("message_delta")) {
            req->stop_reason = event.value("delta").toObject().value("stop_reason").toString();
            req->out_tokens = event.value("usage").toObject().value("output_tokens").toDouble();
        } else if (type == QLatin1String("error")) {
            req->stream_error = event.value("error").toObject().value("message").toString();
        }
    }
}

static QString cleanLine(QString line)
{
    line = line.trimmed();
    // Strip quotes or a "Line 1:" label if the model adds one
    static const QRegularExpression label(QStringLiteral("^(line\\s*\\d\\s*:\\s*)"), QRegularExpression::CaseInsensitiveOption);
    line.remove(label);
    if (line.startsWith(QLatin1Char('"')))
        line.remove(0, 1);
    if (line.endsWith(QLatin1Char('"')))
        line.chop(1);
    return line.trimmed();
}

static bool isNone(const QString &answer)
{
    return answer.trimmed().startsWith(QLatin1String("NONE"));
}

// Shows the translation (first line of the answer). In instant mode it is shown word by word while it is
// written; otherwise once the line is finished. `final` = the answer is complete.
void SubtitleTranslator::updateLine(const std::shared_ptr<Request> &req, bool final)
{
    if (req->no_subtitle || req->line_complete)
        return;
    const QString trimmed = req->answer.trimmed();
    if (isNone(trimmed)) {
        req->no_subtitle = true;
        return;
    }
    // "N", "NO", "NON" could still become NONE: wait
    if (!final && QStringLiteral("NONE").startsWith(trimmed))
        return;

    const int newline = req->answer.indexOf(QLatin1Char('\n'), req->answer.indexOf(QRegularExpression(QStringLiteral("\\S"))));
    const bool complete = final || newline >= 0;
    QString part = newline >= 0 ? req->answer.left(newline) : req->answer;
    if (!complete) {
        if (!config().instant)
            return;
        // Whole words only, so a half-written word never shows
        const int space = part.lastIndexOf(QLatin1Char(' '));
        if (space <= 0)
            return;
        part = part.left(space);
    }
    const QString text = cleanLine(part);
    if (text.isEmpty())
        return;
    if (complete)
        req->line_complete = true;

    // An older line that arrives after a newer one is already on screen is not shown
    if (req->seq < shown_seq) {
        qCInfo(chiakiSubtitles) << "line" << req->seq << "not shown: a newer line is already on screen";
        return;
    }

    const qint64 now = clock.elapsed();
    if (req->seq > shown_seq) {
        shown_seq = req->seq;
        current_box = req->box;
        current_original.clear();
        current_speaker.clear();
        current_text = text;
        current_complete = complete;
        current_serial++;
        emit subtitleChanged();
        emit textChanged();
        emit originalChanged();
        qCInfo(chiakiSubtitles).nospace() << "line " << req->seq << " on screen " << (now - req->seen_ms) << " ms after it was seen ("
                                          << (req->sent_ms - req->seen_ms) << " ms to send, "
                                          << (now - req->sent_ms) << " ms Claude" << (complete ? "" : ", first words") << ")";
    } else if (text != current_text || complete != current_complete) {
        current_text = text;
        current_complete = complete;
        emit textChanged();
        if (complete)
            qCInfo(chiakiSubtitles).nospace() << "line " << req->seq << " complete " << (now - req->seen_ms) << " ms after it was seen";
    }
}

void SubtitleTranslator::finishStream(QNetworkReply *reply, const std::shared_ptr<Request> &req)
{
    reply->deleteLater();
    req->open--;
    // A cancelled or failed copy while the other one is still answering (or already won) changes nothing
    if (req->done || (req->winner && reply != req->winner) || (!req->winner && req->open > 0))
        return;

    if (reply->bytesAvailable())
        readStream(reply, req);
    QByteArray &buffer = req->buffers[reply];
    if (!buffer.isEmpty()) {
        buffer += '\n';
        readStream(reply, req);
    }
    req->done = true;
    in_flight = std::max(0, in_flight - 1);
    const int http = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const Config c = config();

    auto next = [this]() {
        if (!pending_image.isEmpty() && is_active && in_flight < kMaxInFlight) {
            const QByteArray image = pending_image;
            pending_image.clear();
            startRequest(image, pending_media_type, pending_box, pending_seen_ms);
        }
    };

    if (http == 0 || (reply->error() != QNetworkReply::NoError && http < 400)) {
        setStatus(tr("No connection to Claude (%1)").arg(reply->errorString()), true);
        next();
        return;
    }
    if (http != 200) {
        reportHttpError(http, QJsonDocument::fromJson(req->error_bodies.value(reply)).object(), c);
        next();
        return;
    }
    if (!req->stream_error.isEmpty()) {
        qCWarning(chiakiSubtitles) << "Claude stream error" << req->stream_error;
        setStatus(tr("Claude is busy right now, some lines are skipped."), true);
        next();
        return;
    }

    // A backup copy that was cancelled had already been charged for its input
    addCost(c, req->in_tokens * req->replies.size(), req->out_tokens);
    if (status_error)
        setStatus(QString(), false);

    // A declined request or a cut-off answer is simply skipped
    if (req->stop_reason != QLatin1String("end_turn")) {
        qCInfo(chiakiSubtitles) << "Subtitle request skipped, stop_reason" << req->stop_reason;
        next();
        return;
    }

    updateLine(req, true);
    const QStringList lines = req->answer.trimmed().split(QLatin1Char('\n'), Qt::SkipEmptyParts);
    const QString translation = lines.isEmpty() || isNone(req->answer) ? QString() : cleanLine(lines.at(0));
    if (translation.isEmpty()) {
        qCInfo(chiakiSubtitles) << "line" << req->seq << ": Claude found no subtitle in the image";
    } else {
        const QString original = lines.size() > 1 ? cleanLine(lines.mid(1).join(QLatin1Char(' '))) : QString();
        // The English copy arrives last; add it to the line on screen if that is still this one
        if (shown_seq == req->seq && current_original != original) {
            current_original = original;
            emit originalChanged();
        }
        recent_lines.append(QStringLiteral("%1 => %2").arg(original, translation));
        while (recent_lines.size() > kRecentLines)
            recent_lines.removeFirst();
    }
    next();
}

void SubtitleTranslator::addCost(const Config &c, double in_tokens, double out_tokens)
{
    for (const Price &price : kPrices) {
        if (c.model.startsWith(QLatin1String(price.prefix))) {
            cost_usd += (in_tokens * price.in_per_mtok + out_tokens * price.out_per_mtok) / 1e6;
            break;
        }
    }
    request_count++;
    emit usageChanged();
}

void SubtitleTranslator::reportHttpError(int http, const QJsonObject &json, const Config &c)
{
    const QString message = json.value("error").toObject().value("message").toString();
    QString text;
    switch (http) {
    case 401: text = tr("Claude API key not accepted. Check it in Settings."); break;
    case 403: text = tr("This API key may not use %1.").arg(modelLabel(c.model)); break;
    case 404: text = tr("%1 is not available for this API key.").arg(modelLabel(c.model)); break;
    case 429: text = tr("Too many requests to Claude, some lines are skipped."); break;
    case 529:
    case 500:
    case 502:
    case 503: text = tr("Claude is busy right now, some lines are skipped."); break;
    default: text = message.isEmpty() ? tr("Claude error %1").arg(http) : message; break;
    }
    qCWarning(chiakiSubtitles) << "Claude API error" << http << message;
    setStatus(text, true);
}

void SubtitleTranslator::testApiKey()
{
    const Config c = config();
    if (c.api_key.isEmpty()) {
        setStatus(tr("Type your Claude API key first"), true);
        return;
    }
    setStatus(tr("Checking the key…"), false);
    QJsonObject body{
        { "model", c.model },
        { "max_tokens", 256 },
        { "messages", QJsonArray{ QJsonObject{ { "role", "user" }, { "content", "Reply with the single word OK." } } } },
    };
    addSpeedSettings(body, c.model);
    QNetworkReply *reply = network->post(apiRequest(c.api_key), QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        const int http = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QJsonObject json = QJsonDocument::fromJson(reply->readAll()).object();
        const Config c = config();
        if (http == 0 || (reply->error() != QNetworkReply::NoError && http < 400)) {
            setStatus(tr("No connection to Claude (%1)").arg(reply->errorString()), true);
            return;
        }
        if (http != 200) {
            reportHttpError(http, json, c);
            return;
        }
        const QJsonObject usage = json.value("usage").toObject();
        addCost(c, usage.value("input_tokens").toDouble(), usage.value("output_tokens").toDouble());
        setStatus(tr("✓ The key works with %1").arg(modelLabel(c.model)), false);
    });
}

void SubtitleTranslator::setStatus(const QString &text, bool error)
{
    if (status_text == text && status_error == error)
        return;
    status_text = text;
    status_error = error;
    emit statusChanged();
}

void SubtitleTranslator::clearSubtitle()
{
    if (current_text.isEmpty() && current_original.isEmpty())
        return;
    current_text.clear();
    current_original.clear();
    current_speaker.clear();
    current_box = QRectF();
    current_complete = true;
    current_serial++;
    emit subtitleChanged();
    emit textChanged();
    emit originalChanged();
}
