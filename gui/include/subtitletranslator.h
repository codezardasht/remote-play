// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_SUBTITLETRANSLATOR_H
#define CHIAKI_SUBTITLETRANSLATOR_H

#include <QObject>
#include <QString>
#include <QStringList>
#include <QImage>
#include <QRectF>
#include <QRect>
#include <QMutex>
#include <QElapsedTimer>
#include <QAtomicInteger>
#include <vector>
#include <memory>

extern "C" {
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
}

class Settings;
class QNetworkAccessManager;
class QNetworkReply;
class QJsonObject;

// Live subtitle translation with Claude.
//
// About eight times a second the stream hands over a reference to the newest decoded frame.
// A worker thread copies the subtitle area (bottom-centre of the picture, brightness only),
// and checks on this PC whether new subtitle text has appeared and stopped changing.
// Only then is that small image sent to the Claude API, which reads the line and translates it
// into Kurdish Sorani or Arabic. Gameplay without subtitles costs nothing.
class SubtitleTranslator : public QObject
{
    Q_OBJECT
    // The translation on screen. In instant mode it grows word by word while Claude writes it.
    Q_PROPERTY(QString text READ text NOTIFY textChanged)
    Q_PROPERTY(bool complete READ complete NOTIFY textChanged)
    // The English line arrives a moment after the translation
    Q_PROPERTY(QString original READ original NOTIFY originalChanged)
    Q_PROPERTY(QString speaker READ speaker NOTIFY subtitleChanged)
    Q_PROPERTY(int serial READ serial NOTIFY subtitleChanged)
    // Where the game drew the line that `text` translates, as fractions of the video frame (0..1)
    Q_PROPERTY(QRectF box READ box NOTIFY subtitleChanged)
    // Where the game's subtitle text is right now (empty when there is none)
    Q_PROPERTY(QRectF liveBox READ liveBox NOTIFY liveBoxChanged)
    // Cover style: a tiny picture of the scene around the game's line (shown blurred over it) and where it goes
    Q_PROPERTY(QString coverImage READ coverImage NOTIFY coverChanged)
    Q_PROPERTY(QRectF coverBox READ coverBox NOTIFY coverChanged)
    Q_PROPERTY(bool active READ active NOTIFY activeChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(bool statusIsError READ statusIsError NOTIFY statusChanged)
    Q_PROPERTY(double costUsd READ costUsd NOTIFY usageChanged)
    Q_PROPERTY(int requestCount READ requestCount NOTIFY usageChanged)

public:
    explicit SubtitleTranslator(QObject *parent = nullptr);
    ~SubtitleTranslator() override;

    void setSettings(Settings *settings) { this->settings = settings; }

    // Main thread, about once a second: re-read the settings and whether a stream is running
    void refresh(bool session_running);

    // Decoder thread: cheap check, then hand over a new reference (av_frame_clone) of the frame
    bool wantsFrame();
    void submitFrame(AVFrame *frame);

    QString text() const { return current_text; }
    bool complete() const { return current_complete; }
    QString original() const { return current_original; }
    QString speaker() const { return current_speaker; }
    int serial() const { return current_serial; }
    QRectF box() const { return current_box; }
    QRectF liveBox() const { return live_box; }
    QString coverImage() const { return cover_image; }
    QRectF coverBox() const { return cover_box; }
    bool active() const { return is_active; }
    QString status() const { return status_text; }
    bool statusIsError() const { return status_error; }
    double costUsd() const { return cost_usd; }
    int requestCount() const { return request_count; }

    // Settings page: send a tiny request to check the key and model
    Q_INVOKABLE void testApiKey();

signals:
    // A new line started (position and timing are decided once per line)
    void subtitleChanged();
    void textChanged();
    void originalChanged();
    // The game's own subtitle went away (so ours can fade out)
    void sourceCleared();
    void activeChanged();
    void liveBoxChanged();
    void coverChanged();
    void statusChanged();
    void usageChanged();

private:
    struct Config {
        bool enabled = false;
        QString api_key;
        QString model;
        QString language;   // "ckb" or "ar"
        bool instant = true; // show words while they are written
        bool cover = false;  // cover style: build the scene patch
    };

    void processFrame(AVFrame *frame);          // worker thread
    struct Request;
    void sendImage(const QByteArray &image, const QString &media_type, const QRectF &box, qint64 seen_ms);     // main thread
    void startRequest(const QByteArray &image, const QString &media_type, const QRectF &box, qint64 seen_ms);
    void sendCopy(const std::shared_ptr<Request> &req);
    void readStream(QNetworkReply *reply, const std::shared_ptr<Request> &req);
    void updateLine(const std::shared_ptr<Request> &req, bool final);
    void finishStream(QNetworkReply *reply, const std::shared_ptr<Request> &req);
    void addCost(const Config &c, double in_tokens, double out_tokens);
    void reportHttpError(int http, const QJsonObject &json, const Config &c);
    void setLiveBox(const QRectF &box);
    void setCover(const QString &image, const QRectF &box);
    QString scenePatch(const AVFrame *sw, const AVPixFmtDescriptor *desc, int width, int height,
                       const QRect &text_px, bool full_range, QRectF &patch_box);
    void setStatus(const QString &text, bool error);
    void clearSubtitle();
    QByteArray buildRequest(const Config &cfg, const QByteArray &image, const QString &media_type) const;
    Config config() const;

    Settings *settings = nullptr;
    QNetworkAccessManager *network = nullptr;

    // Shared between threads
    mutable QMutex config_mutex;
    Config cfg;
    QAtomicInteger<int> enabled_flag = 0;
    QAtomicInteger<int> worker_busy = 0;
    QAtomicInteger<qint64> last_grab_ms = 0;
    QAtomicInteger<int> worker_reset = 0;
    QElapsedTimer clock;

    // Worker thread only (one job at a time, guarded by worker_busy)
    std::vector<quint8> prev_mask;
    std::vector<quint8> sent_mask;
    int empty_samples = 0;
    QRectF last_live_box;
    qint64 last_send_ms = -100000;

    // Main thread only
    int in_flight = 0;
    int next_seq = 0;
    int shown_seq = 0;
    QByteArray pending_image;
    qint64 pending_seen_ms = 0;
    QString pending_media_type;
    QRectF pending_box;
    QRectF current_box;
    QRectF live_box;
    QString cover_image;
    QRectF cover_box;
    QStringList recent_lines;   // "speaker: original => translation", for consistent names and wording
    QString current_text;
    bool current_complete = true;
    QString current_original;
    QString current_speaker;
    int current_serial = 0;
    bool is_active = false;
    QString status_text;
    bool status_error = false;
    double cost_usd = 0.0;
    int request_count = 0;
};

#endif // CHIAKI_SUBTITLETRANSLATOR_H
