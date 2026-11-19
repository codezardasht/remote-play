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
#include <QThread>
#include <QThreadPool>
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
constexpr int kMaxInFlight = 2;            // a new line doesn't wait for the previous answer
constexpr int kCell = 4;                   // change-detection grid: 4x4 pixel cells
constexpr int kMinInkCells = 30;           // less than this = no text in the area
constexpr double kStableIoU = 0.80;        // same text as the previous look
constexpr double kNewIoU = 0.60;           // different from the last line sent
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
        "Fill the fields in this order:\n"
        "- original: the subtitle or objective text exactly as written, joined into one line. Leave out HUD numbers, "
        "button prompts and text that belongs to the game world (signs, posters, number plates).\n"
        "- speaker: the speaker's name if the line starts with one (for example \"FRANKLIN:\"), otherwise empty. "
        "Do not repeat it in original.\n"
        "- translation: original translated into %1. Use a natural spoken style the player understands at a glance, "
        "about as short as the original. Keep the meaning, tone and attitude of slang and jokes instead of translating "
        "word by word. Swearing may stay, but don't make it stronger.\n"
        "%2"
        "- has_subtitle: false when there is no readable subtitle or objective text, or it is too cut off to "
        "understand; then leave the other fields empty.\n")
        .arg(languageName(language), lang_rules);
}

// Written by hand because QJsonObject sorts keys: the answer follows this order, and the translation
// must come before the English copy so it can be shown as soon as it is written.
const char *kSubtitleSchema =
    R"({"type":"object","properties":{)"
    R"("has_subtitle":{"type":"boolean"},)"
    R"("speaker":{"type":"string"},)"
    R"("translation":{"type":"string"},)"
    R"("original":{"type":"string"}},)"
    R"("required":["has_subtitle","speaker","translation","original"],"additionalProperties":false})";

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

    const bool stable = iou(mask, prev_mask) >= kStableIoU;   // text stopped changing (typewriter effects, fades)
    const bool is_new = iou(mask, sent_mask) < kNewIoU;       // not the line we already sent
    const qint64 now = clock.elapsed();
    const bool send = stable && is_new && now - last_send_ms >= kMinSendGapMs;
    prev_mask = mask;
    if (!send)
        return;

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
    addSpeedSettings(body, c.model, QJsonObject{ { "type", "json_schema" }, { "schema", "@SCHEMA@" } });
    QByteArray json = QJsonDocument(body).toJson(QJsonDocument::Compact);
    json.replace("\"@SCHEMA@\"", kSubtitleSchema);
    return json;
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

// Reading fields out of a JSON answer that is still being written
int valueStart(const QString &s, const char *key)
{
    const QString quoted = QStringLiteral("\"%1\"").arg(QLatin1String(key));
    int i = s.indexOf(quoted);
    if (i < 0)
        return -1;
    i += quoted.size();
    while (i < s.size() && s[i].isSpace())
        i++;
    if (i >= s.size() || s[i] != QLatin1Char(':'))
        return -1;
    i++;
    while (i < s.size() && s[i].isSpace())
        i++;
    return i < s.size() ? i : -1;
}

bool partialString(const QString &s, const char *key, QString &out)
{
    const int i = valueStart(s, key);
    if (i < 0 || s[i] != QLatin1Char('"'))
        return false;
    bool escaped = false;
    for (int j = i + 1; j < s.size(); j++) {
        if (escaped) {
            escaped = false;
        } else if (s[j] == QLatin1Char('\\')) {
            escaped = true;
        } else if (s[j] == QLatin1Char('"')) {
            const QJsonDocument doc = QJsonDocument::fromJson((QStringLiteral("[") + s.mid(i, j - i + 1) + QStringLiteral("]")).toUtf8());
            if (!doc.isArray())
                return false;
            out = doc.array().at(0).toString();
            return true;
        }
    }
    return false;
}

// -1 = not written yet, 0 = false, 1 = true
int partialBool(const QString &s, const char *key)
{
    const int i = valueStart(s, key);
    if (i < 0)
        return -1;
    if (s.mid(i, 4) == QLatin1String("true"))
        return 1;
    if (s.mid(i, 5) == QLatin1String("false"))
        return 0;
    return -1;
}

} // namespace

struct SubtitleTranslator::Request {
    int seq = 0;
    QRectF box;
    qint64 seen_ms = 0;
    qint64 sent_ms = 0;
    QByteArray buffer;      // unparsed stream data
    QByteArray error_body;  // body of a failed request
    QString answer;         // the JSON answer as written so far
    bool shown = false;
    bool no_subtitle = false;
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
    in_flight++;
    QNetworkReply *reply = network->post(apiRequest(c.api_key), buildRequest(c, image, media_type));
    // The answer is streamed, so the translation can be shown as soon as it is written
    connect(reply, &QNetworkReply::readyRead, this, [this, reply, req]() { readStream(reply, req); });
    connect(reply, &QNetworkReply::finished, this, [this, reply, req]() { finishStream(reply, req); });
}

void SubtitleTranslator::readStream(QNetworkReply *reply, const std::shared_ptr<Request> &req)
{
    const int http = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (http != 200) {
        req->error_body += reply->readAll();
        return;
    }
    req->buffer += reply->readAll();
    int newline;
    while ((newline = req->buffer.indexOf('\n')) >= 0) {
        const QByteArray line = req->buffer.left(newline).trimmed();
        req->buffer.remove(0, newline + 1);
        if (!line.startsWith("data:"))
            continue;
        const QJsonObject event = QJsonDocument::fromJson(line.mid(5).trimmed()).object();
        const QString type = event.value("type").toString();
        if (type == QLatin1String("message_start")) {
            req->in_tokens = event.value("message").toObject().value("usage").toObject().value("input_tokens").toDouble();
        } else if (type == QLatin1String("content_block_delta")) {
            const QJsonObject delta = event.value("delta").toObject();
            if (delta.value("type").toString() == QLatin1String("text_delta")) {
                req->answer += delta.value("text").toString();
                showEarly(req);
            }
        } else if (type == QLatin1String("message_delta")) {
            req->stop_reason = event.value("delta").toObject().value("stop_reason").toString();
            req->out_tokens = event.value("usage").toObject().value("output_tokens").toDouble();
        } else if (type == QLatin1String("error")) {
            req->stream_error = event.value("error").toObject().value("message").toString();
        }
    }
}

void SubtitleTranslator::showEarly(const std::shared_ptr<Request> &req)
{
    if (req->shown || req->no_subtitle)
        return;
    const int has = partialBool(req->answer, "has_subtitle");
    if (has == 0) {
        req->no_subtitle = true;
        return;
    }
    QString translation;
    if (has != 1 || !partialString(req->answer, "translation", translation))
        return;
    QString speaker;
    partialString(req->answer, "speaker", speaker);
    showTranslation(req, translation.trimmed(), speaker.trimmed());
}

void SubtitleTranslator::showTranslation(const std::shared_ptr<Request> &req, const QString &translation, const QString &speaker)
{
    req->shown = true;
    // An older line that finishes after a newer one is already on screen is not shown
    if (translation.isEmpty() || req->seq <= shown_seq)
        return;
    shown_seq = req->seq;
    current_text = translation;
    current_speaker = speaker;
    current_original.clear();
    current_box = req->box;
    current_serial++;
    emit subtitleChanged();
    emit originalChanged();
    const qint64 now = clock.elapsed();
    qCInfo(chiakiSubtitles).nospace() << "line on screen " << (now - req->seen_ms) << " ms after it was seen ("
                                      << (req->sent_ms - req->seen_ms) << " ms to send, "
                                      << (now - req->sent_ms) << " ms Claude)";
}

void SubtitleTranslator::finishStream(QNetworkReply *reply, const std::shared_ptr<Request> &req)
{
    reply->deleteLater();
    if (reply->bytesAvailable())
        readStream(reply, req);
    if (!req->buffer.isEmpty()) {
        req->buffer += '\n';
        readStream(reply, req);
    }
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
        reportHttpError(http, QJsonDocument::fromJson(req->error_body).object(), c);
        next();
        return;
    }
    if (!req->stream_error.isEmpty()) {
        qCWarning(chiakiSubtitles) << "Claude stream error" << req->stream_error;
        setStatus(tr("Claude is busy right now, some lines are skipped."), true);
        next();
        return;
    }

    addCost(c, req->in_tokens, req->out_tokens);
    if (status_error)
        setStatus(QString(), false);

    // A declined request or a cut-off answer is simply skipped
    if (req->stop_reason != QLatin1String("end_turn")) {
        qCInfo(chiakiSubtitles) << "Subtitle request skipped, stop_reason" << req->stop_reason;
        next();
        return;
    }

    const QJsonObject result = QJsonDocument::fromJson(req->answer.toUtf8()).object();
    const QString translation = result.value("translation").toString().trimmed();
    if (result.value("has_subtitle").toBool() && !translation.isEmpty()) {
        const QString speaker = result.value("speaker").toString().trimmed();
        const QString original = result.value("original").toString().trimmed();
        if (!req->shown)
            showTranslation(req, translation, speaker);
        // The English copy arrives last; add it to the line on screen if that is still this one
        if (shown_seq == req->seq && current_original != original) {
            current_original = original;
            emit originalChanged();
        }
        recent_lines.append(QStringLiteral("%1%2 => %3")
                                .arg(speaker.isEmpty() ? QString() : speaker + QStringLiteral(": "), original, translation));
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
    current_serial++;
    emit subtitleChanged();
    emit originalChanged();
}
