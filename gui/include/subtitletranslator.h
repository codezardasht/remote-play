// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_SUBTITLETRANSLATOR_H
#define CHIAKI_SUBTITLETRANSLATOR_H

#include <QObject>
#include <QString>
#include <QStringList>
#include <QImage>
#include <QMutex>
#include <QElapsedTimer>
#include <QAtomicInteger>
#include <vector>

extern "C" {
#include <libavutil/frame.h>
}

class Settings;
class QNetworkAccessManager;
class QNetworkReply;

// Live subtitle translation with Claude.
//
// About three times a second the stream hands over a reference to the newest decoded frame.
// A worker thread copies the subtitle area (bottom-centre of the picture, brightness only),
// and checks on this PC whether new subtitle text has appeared and stopped changing.
// Only then is that small image sent to the Claude API, which reads the line and translates it
// into Kurdish Sorani or Arabic. Gameplay without subtitles costs nothing.
class SubtitleTranslator : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString text READ text NOTIFY subtitleChanged)
    Q_PROPERTY(QString original READ original NOTIFY subtitleChanged)
    Q_PROPERTY(QString speaker READ speaker NOTIFY subtitleChanged)
    Q_PROPERTY(int serial READ serial NOTIFY subtitleChanged)
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
    QString original() const { return current_original; }
    QString speaker() const { return current_speaker; }
    int serial() const { return current_serial; }
    bool active() const { return is_active; }
    QString status() const { return status_text; }
    bool statusIsError() const { return status_error; }
    double costUsd() const { return cost_usd; }
    int requestCount() const { return request_count; }

    // Settings page: send a tiny request to check the key and model
    Q_INVOKABLE void testApiKey();

signals:
    void subtitleChanged();
    // The game's own subtitle went away (so ours can fade out)
    void sourceCleared();
    void activeChanged();
    void statusChanged();
    void usageChanged();

private:
    struct Config {
        bool enabled = false;
        QString api_key;
        QString model;
        QString language;   // "ckb" or "ar"
    };

    void processFrame(AVFrame *frame);          // worker thread
    void sendImage(const QByteArray &image, const QString &media_type);     // main thread
    void startRequest(const QByteArray &image, const QString &media_type);
    void handleReply(QNetworkReply *reply, bool is_test);
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
    qint64 last_send_ms = -100000;

    // Main thread only
    bool request_in_flight = false;
    QByteArray pending_image;
    QString pending_media_type;
    QStringList recent_lines;   // "speaker: original => translation", for consistent names and wording
    QString current_text;
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
