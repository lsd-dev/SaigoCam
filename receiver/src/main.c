/*
 * SaigoCam low-latency RTSP receiver (prototype).
 * Part of SaigoCam, licensed under the GNU General Public License v3; see ../../LICENSE.
 *
 * Design: one thread receives and decodes, the main thread displays. They
 * share a single-frame "mailbox": the decoder always overwrites it with the
 * newest frame, so nothing queues up and the display never falls behind. A
 * frame that is overwritten before it was shown is counted as dropped.
 *
 * There is deliberately no jitter buffer, no frame queue and no pts-based
 * pacing: a frame is shown as soon as it is decoded.
 */
#define SDL_MAIN_HANDLED
#include <SDL.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/base64.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

#include <ctype.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "early_nal.h"
#include "overlay.h"

#if LIBAVFORMAT_VERSION_MAJOR < 59
#error "FFmpeg 5.0 or newer is required (before 5.0 the RTSP 'timeout' option meant 'listen')."
#endif
#if !SDL_VERSION_ATLEAST(2, 0, 16)
#error "SDL 2.0.16 or newer is required (SDL_UpdateNVTexture, event-driven SDL_WaitEventTimeout)."
#endif

#define STALL_TIMEOUT_MS 5000   /* reconnect if no packet arrives for this long */
#define MAX_BACKOFF_MS 4000

typedef struct Options {
    const char *url;
    const char *transport; /* "tcp" or "udp" */
    const char *hwdec;     /* NULL = software decode */
    const char *renderer;  /* SDL render driver, NULL = SDL default */
    int early;
    int vsync;
    int fast;
    int probe;
    int overlay;
    int fullscreen;
    int verbose;
} Options;

/* State shared between the decode thread and the display thread, guarded by mtx. */
typedef struct Shared {
    SDL_mutex *mtx;
    AVFrame *slot;        /* newest decoded frame not yet taken by the display */
    int slot_full;
    int64_t slot_t_dec;   /* av_gettime_relative() when the decoder returned it */
    int64_t rx2dec_sum_us;
    int rx2dec_n;
    char status[96];
    char stream_info[96];
} Shared;

typedef struct App {
    Options opt;
    char url_redacted[512];
    char secret_pass[256]; /* password from the URL; used only to redact logs */
    char secret_b64[512];  /* base64("user:password") as sent in Basic auth */
    Uint32 wake_event;
    SDL_atomic_t quit;
    SDL_atomic_t wake_pending;
    SDL_atomic_t last_activity; /* SDL_GetTicks() of the last network progress */
    SDL_atomic_t n_decoded;
    SDL_atomic_t n_dropped;
    SDL_atomic_t n_dec_errors;
    SDL_atomic_t n_misfires;
    SDL_atomic_t bytes;         /* video bytes since the display thread last read it */
    SDL_atomic_t reorder;       /* decoder has_b_frames: frames it may hold back */
    Shared sh;
} App;

/* ------------------------------------------------------------------------ */
/* Credentials: never print them                                            */
/* ------------------------------------------------------------------------ */

static App *g_app; /* the FFmpeg log callback has no user pointer */

static const char *errstr(int err, char *buf, size_t size)
{
    av_strerror(err, buf, size);
    return buf;
}

static int contains_ci(const char *hay, const char *needle)
{
    size_t n = strlen(needle);
    for (; *hay; hay++) {
        size_t i = 0;
        while (i < n && hay[i] &&
               tolower((unsigned char)hay[i]) == tolower((unsigned char)needle[i]))
            i++;
        if (i == n)
            return 1;
    }
    return 0;
}

static int line_has_secret(const char *line)
{
    if (contains_ci(line, "authorization"))
        return 1;
    if (g_app && g_app->secret_pass[0] && strstr(line, g_app->secret_pass))
        return 1;
    if (g_app && g_app->secret_b64[0] && strstr(line, g_app->secret_b64))
        return 1;
    return 0;
}

/* FFmpeg logs whole RTSP requests at debug level, including the
 * Authorization header, so every FFmpeg log line goes through this filter. */
static void ff_log_cb(void *avcl, int level, const char *fmt, va_list vl)
{
    static int print_prefix = 1;
    char line[2048];

    if (level > av_log_get_level())
        return;
    av_log_format_line2(avcl, level, fmt, vl, line, sizeof(line), &print_prefix);
    if (line_has_secret(line)) {
        fputs("[ffmpeg log line hidden: it may contain credentials]\n", stderr);
        return;
    }
    fputs(line, stderr);
}

/* Builds the redacted URL for display and remembers the password so the log
 * filter can catch it. Format: rtsp://user:password@host:port/path */
static void prepare_url_secrets(App *app)
{
    const char *url = app->opt.url;
    const char *p = strstr(url, "://");
    const char *at, *colon;
    size_t userinfo_len, pass_len;

    snprintf(app->url_redacted, sizeof(app->url_redacted), "%s", url);
    if (!p)
        return;
    p += 3;
    at = strrchr(p, '@');
    if (!at)
        return;
    userinfo_len = (size_t)(at - p);
    colon = memchr(p, ':', userinfo_len);
    if (!colon)
        return;

    pass_len = (size_t)(at - colon - 1);
    if (pass_len >= sizeof(app->secret_pass))
        pass_len = sizeof(app->secret_pass) - 1; /* a prefix still matches */
    memcpy(app->secret_pass, colon + 1, pass_len);
    app->secret_pass[pass_len] = '\0';

    if (userinfo_len > 0 && userinfo_len <= 380) /* fits AV_BASE64_SIZE(380) <= 512 */
        av_base64_encode(app->secret_b64, sizeof(app->secret_b64),
                         (const uint8_t *)p, (int)userinfo_len);

    snprintf(app->url_redacted, sizeof(app->url_redacted), "%.*s:***%s",
             (int)(colon - url), url, at);
}

/* ------------------------------------------------------------------------ */
/* Decode thread                                                            */
/* ------------------------------------------------------------------------ */

typedef struct Session {
    App *app;
    AVFormatContext *fmt;
    int fmt_opened;
    AVCodecContext *cc;
    AVBufferRef *hw_dev;
    enum AVPixelFormat hw_pix_fmt; /* AV_PIX_FMT_NONE when decoding in software */
    int stream_index;
    AVPacket *pkt;
    AVPacket *chunk;               /* --early: assembled NAL units */
    AVFrame *frame;
    AVFrame *sw_frame;             /* hardware frames are copied here */
    EarlyNal early;
    int64_t t_rx;                  /* when the packet being decoded was read */
    int got_frames;
} Session;

static void set_status(App *app, const char *fmt, ...)
{
    va_list vl;
    SDL_LockMutex(app->sh.mtx);
    va_start(vl, fmt);
    vsnprintf(app->sh.status, sizeof(app->sh.status), fmt, vl);
    va_end(vl);
    SDL_UnlockMutex(app->sh.mtx);
}

static int interrupt_cb(void *opaque)
{
    App *app = opaque;
    Uint32 idle;
    if (SDL_AtomicGet(&app->quit))
        return 1;
    idle = SDL_GetTicks() - (Uint32)SDL_AtomicGet(&app->last_activity);
    return idle > STALL_TIMEOUT_MS;
}

static enum AVPixelFormat get_hw_format(AVCodecContext *cc, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *want = cc->opaque;
    const enum AVPixelFormat *p;

    for (p = fmts; *p != AV_PIX_FMT_NONE; p++)
        if (*p == *want)
            return *p;
    /* The hardware path refused this stream: fall back to software. */
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (d && !(d->flags & AV_PIX_FMT_FLAG_HWACCEL)) {
            av_log(cc, AV_LOG_WARNING, "hardware decode not available for this stream, using software\n");
            return *p;
        }
    }
    return AV_PIX_FMT_NONE;
}

static int try_hw_device(Session *s, const AVCodec *dec, const char *name)
{
    enum AVHWDeviceType type = av_hwdevice_find_type_by_name(name);
    enum AVPixelFormat pix_fmt = AV_PIX_FMT_NONE;
    int i;

    if (type == AV_HWDEVICE_TYPE_NONE)
        return -1;
    for (i = 0;; i++) {
        const AVCodecHWConfig *cfg = avcodec_get_hw_config(dec, i);
        if (!cfg)
            return -1;
        if ((cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) &&
            cfg->device_type == type) {
            pix_fmt = cfg->pix_fmt;
            break;
        }
    }
    if (av_hwdevice_ctx_create(&s->hw_dev, type, NULL, NULL, 0) < 0)
        return -1;
    s->hw_pix_fmt = pix_fmt;
    s->cc->hw_device_ctx = av_buffer_ref(s->hw_dev);
    s->cc->opaque = &s->hw_pix_fmt;
    s->cc->get_format = get_hw_format;
    return 0;
}

/* Returns the device name in use, or "sw". */
static const char *setup_hwdec(Session *s, const AVCodec *dec, const char *want)
{
    static const char *const auto_order[] = {
        "d3d11va", "dxva2", "cuda", "qsv", "vaapi", "videotoolbox", NULL
    };
    int i;

    if (!want || !strcmp(want, "none"))
        return "sw";
    if (strcmp(want, "auto")) {
        if (try_hw_device(s, dec, want) == 0)
            return want;
        fprintf(stderr, "warning: hardware decoder '%s' not available, using software\n", want);
        return "sw";
    }
    for (i = 0; auto_order[i]; i++)
        if (try_hw_device(s, dec, auto_order[i]) == 0)
            return auto_order[i];
    fprintf(stderr, "warning: no hardware decoder available, using software\n");
    return "sw";
}

/* Puts a decoded frame in the mailbox, replacing (dropping) any frame the
 * display has not taken yet. */
static void publish_frame(Session *s, AVFrame *f)
{
    App *app = s->app;
    AVFrame *out = f;
    int64_t t_dec;

    if (s->hw_pix_fmt != AV_PIX_FMT_NONE && f->format == s->hw_pix_fmt) {
        av_frame_unref(s->sw_frame);
        if (av_hwframe_transfer_data(s->sw_frame, f, 0) < 0) {
            SDL_AtomicIncRef(&app->n_dec_errors);
            av_frame_unref(f);
            return;
        }
        av_frame_copy_props(s->sw_frame, f);
        out = s->sw_frame;
    }

    t_dec = av_gettime_relative();
    SDL_LockMutex(app->sh.mtx);
    if (app->sh.slot_full)
        SDL_AtomicIncRef(&app->n_dropped);
    av_frame_unref(app->sh.slot);
    av_frame_move_ref(app->sh.slot, out);
    app->sh.slot_full = 1;
    app->sh.slot_t_dec = t_dec;
    app->sh.rx2dec_sum_us += t_dec - s->t_rx;
    app->sh.rx2dec_n++;
    if (s->got_frames++ == 0)
        snprintf(app->sh.status, sizeof(app->sh.status), "STREAMING");
    SDL_UnlockMutex(app->sh.mtx);
    av_frame_unref(f);

    SDL_AtomicIncRef(&app->n_decoded);
    /* Wake the display thread, but never pile up wake-up events. */
    if (SDL_AtomicCAS(&app->wake_pending, 0, 1)) {
        SDL_Event ev;
        SDL_zero(ev);
        ev.type = app->wake_event;
        SDL_PushEvent(&ev);
    }
}

static void drain_decoder(Session *s)
{
    int ret;
    while ((ret = avcodec_receive_frame(s->cc, s->frame)) >= 0)
        publish_frame(s, s->frame);
    if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF)
        SDL_AtomicIncRef(&s->app->n_dec_errors);
    SDL_AtomicSet(&s->app->reorder, s->cc->has_b_frames);
}

static void decode_packet(Session *s, AVPacket *p)
{
    int ret = avcodec_send_packet(s->cc, p);
    if (ret == AVERROR(EAGAIN)) {
        drain_decoder(s);
        ret = avcodec_send_packet(s->cc, p);
    }
    if (ret < 0)
        SDL_AtomicIncRef(&s->app->n_dec_errors); /* corrupt data; keep going */
    drain_decoder(s);
}

static void early_emit(void *opaque, const uint8_t *data, size_t size)
{
    Session *s = opaque;
    if (size > (size_t)(INT_MAX - AV_INPUT_BUFFER_PADDING_SIZE))
        return;
    if (av_new_packet(s->chunk, (int)size) < 0)
        return;
    memcpy(s->chunk->data, data, size);
    decode_packet(s, s->chunk);
    av_packet_unref(s->chunk);
}

/* One connection: open, receive and decode until error, stall or quit.
 * Returns the number of frames decoded. */
static int run_session(App *app)
{
    Session s;
    AVDictionary *opts = NULL;
    AVDictionaryEntry *e = NULL;
    AVCodecParameters *par;
    const AVCodec *dec;
    const char *hw_name = "sw";
    char err[AV_ERROR_MAX_STRING_SIZE];
    unsigned i;
    int ret;

    memset(&s, 0, sizeof(s));
    s.app = app;
    s.hw_pix_fmt = AV_PIX_FMT_NONE;
    early_nal_init(&s.early);

    set_status(app, "CONNECTING");
    SDL_AtomicSet(&app->last_activity, (int)SDL_GetTicks());

    s.fmt = avformat_alloc_context();
    s.pkt = av_packet_alloc();
    s.chunk = av_packet_alloc();
    s.frame = av_frame_alloc();
    s.sw_frame = av_frame_alloc();
    if (!s.fmt || !s.pkt || !s.chunk || !s.frame || !s.sw_frame) {
        set_status(app, "ERROR: OUT OF MEMORY");
        goto out;
    }
    s.fmt->interrupt_callback.callback = interrupt_cb;
    s.fmt->interrupt_callback.opaque = app;

    /* Low-latency demuxer settings. See README "How it keeps latency low". */
    av_dict_set(&opts, "rtsp_transport", app->opt.transport, 0);
    /* "+" keeps FFmpeg's default flags and adds ours. */
    av_dict_set(&opts, "fflags", app->opt.early ? "+nobuffer+noparse+nofillin" : "+nobuffer", 0);
    av_dict_set(&opts, "max_delay", "0", 0);          /* UDP: no RTP reordering wait */
    av_dict_set(&opts, "reorder_queue_size", "0", 0);
    av_dict_set(&opts, "probesize", "32", 0);
    av_dict_set(&opts, "analyzeduration", "0", 0);
    av_dict_set(&opts, "allowed_media_types", "video", 0);
    av_dict_set(&opts, "timeout", "5000000", 0);      /* socket I/O timeout, microseconds */
    av_dict_set(&opts, "buffer_size", "2097152", 0);  /* UDP socket capacity; adds no delay */

    ret = avformat_open_input(&s.fmt, app->opt.url, NULL, &opts);
    if (ret < 0) {
        /* avformat_open_input frees the context on failure. */
        fprintf(stderr, "could not open %s: %s\n", app->url_redacted, errstr(ret, err, sizeof(err)));
        set_status(app, "ERROR: %s", err);
        goto out;
    }
    s.fmt_opened = 1;
    while ((e = av_dict_get(opts, "", e, AV_DICT_IGNORE_SUFFIX)))
        fprintf(stderr, "warning: option '%s' was not used by this FFmpeg build\n", e->key);

    if (app->opt.probe) {
        ret = avformat_find_stream_info(s.fmt, NULL);
        if (ret < 0)
            fprintf(stderr, "warning: stream probing failed: %s\n", errstr(ret, err, sizeof(err)));
    }

    s.stream_index = av_find_best_stream(s.fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (s.stream_index < 0) {
        set_status(app, "ERROR: NO VIDEO STREAM");
        goto out;
    }
    for (i = 0; i < s.fmt->nb_streams; i++)
        if ((int)i != s.stream_index)
            s.fmt->streams[i]->discard = AVDISCARD_ALL;
    par = s.fmt->streams[s.stream_index]->codecpar;

    if (app->opt.early && par->codec_id != AV_CODEC_ID_H264) {
        set_status(app, "ERROR: --EARLY NEEDS H.264");
        goto out;
    }
    dec = avcodec_find_decoder(par->codec_id);
    if (!dec) {
        set_status(app, "ERROR: NO DECODER");
        goto out;
    }
    s.cc = avcodec_alloc_context3(dec);
    if (!s.cc || avcodec_parameters_to_context(s.cc, par) < 0) {
        set_status(app, "ERROR: DECODER SETUP");
        goto out;
    }
    /* LOW_DELAY also disables frame threading, which would otherwise hold
     * back one frame per decoder thread. Slice threading adds no delay. */
    s.cc->flags |= AV_CODEC_FLAG_LOW_DELAY;
    s.cc->thread_type = FF_THREAD_SLICE;
    if (app->opt.fast)
        s.cc->flags2 |= AV_CODEC_FLAG2_FAST;
    if (app->opt.early)
        s.cc->flags2 |= AV_CODEC_FLAG2_CHUNKS; /* input is NAL units, not whole frames */
    hw_name = setup_hwdec(&s, dec, app->opt.hwdec);

    ret = avcodec_open2(s.cc, dec, NULL);
    if (ret < 0) {
        fprintf(stderr, "could not open decoder: %s\n", errstr(ret, err, sizeof(err)));
        set_status(app, "ERROR: DECODER OPEN");
        goto out;
    }

    SDL_LockMutex(app->sh.mtx);
    snprintf(app->sh.stream_info, sizeof(app->sh.stream_info), "%s %s %s %s",
             dec->name, app->opt.transport, hw_name, app->opt.early ? "EARLY" : "PARSER");
    snprintf(app->sh.status, sizeof(app->sh.status), "WAITING FOR KEYFRAME");
    SDL_UnlockMutex(app->sh.mtx);
    fprintf(stderr, "connected to %s (%s)\n", app->url_redacted, app->sh.stream_info);

    while (!SDL_AtomicGet(&app->quit)) {
        ret = av_read_frame(s.fmt, s.pkt);
        if (ret == AVERROR(EAGAIN)) {
            SDL_Delay(1);
            continue;
        }
        if (ret < 0)
            break;
        SDL_AtomicSet(&app->last_activity, (int)SDL_GetTicks());
        if (s.pkt->stream_index == s.stream_index) {
            s.t_rx = av_gettime_relative();
            SDL_AtomicAdd(&app->bytes, s.pkt->size);
            if (app->opt.early) {
                int before = s.early.misfires;
                early_nal_push(&s.early, s.pkt->data, (size_t)s.pkt->size, early_emit, &s);
                if (s.early.misfires != before)
                    SDL_AtomicAdd(&app->n_misfires, s.early.misfires - before);
            } else {
                decode_packet(&s, s.pkt);
            }
        }
        av_packet_unref(s.pkt);
    }
    if (ret < 0 && !SDL_AtomicGet(&app->quit))
        fprintf(stderr, "stream ended: %s\n", errstr(ret, err, sizeof(err)));

out:
    avcodec_free_context(&s.cc);
    av_buffer_unref(&s.hw_dev);
    if (s.fmt_opened)
        avformat_close_input(&s.fmt);
    else
        avformat_free_context(s.fmt);
    av_dict_free(&opts);
    av_packet_free(&s.pkt);
    av_packet_free(&s.chunk);
    av_frame_free(&s.frame);
    av_frame_free(&s.sw_frame);
    early_nal_free(&s.early);
    return s.got_frames;
}

static int decode_thread(void *arg)
{
    App *app = arg;
    int backoff_ms = 500, attempt = 0, waited;

    while (!SDL_AtomicGet(&app->quit)) {
        if (run_session(app) > 0) {
            backoff_ms = 500;
            attempt = 0;
        }
        if (SDL_AtomicGet(&app->quit))
            break;
        attempt++;
        set_status(app, "RECONNECTING IN %.1f S (TRY %d)", backoff_ms / 1000.0, attempt);
        for (waited = 0; waited < backoff_ms && !SDL_AtomicGet(&app->quit); waited += 50)
            SDL_Delay(50);
        backoff_ms = backoff_ms * 2 > MAX_BACKOFF_MS ? MAX_BACKOFF_MS : backoff_ms * 2;
    }
    return 0;
}

/* ------------------------------------------------------------------------ */
/* Display (main thread)                                                    */
/* ------------------------------------------------------------------------ */

typedef struct View {
    SDL_Window *win;
    SDL_Renderer *ren;
    SDL_Texture *tex;
    int tex_w, tex_h, tex_full_range;
    Uint32 tex_fmt;
    AVFrame *frame; /* the frame on screen, owned by the display thread */
    int warned_format;
} View;

static int upload_frame(View *v, const AVFrame *f)
{
    Uint32 fmt;
    int full_range;

    switch (f->format) {
    case AV_PIX_FMT_YUV420P:
    case AV_PIX_FMT_YUVJ420P:
        fmt = SDL_PIXELFORMAT_IYUV;
        break;
    case AV_PIX_FMT_NV12: /* what hardware decoders hand back */
        fmt = SDL_PIXELFORMAT_NV12;
        break;
    default:
        if (!v->warned_format) {
            const char *name = av_get_pix_fmt_name((enum AVPixelFormat)f->format);
            fprintf(stderr, "error: pixel format %s is not supported by this prototype\n",
                    name ? name : "?");
            v->warned_format = 1;
        }
        return -1;
    }
    full_range = f->color_range == AVCOL_RANGE_JPEG || f->format == AV_PIX_FMT_YUVJ420P;

    if (!v->tex || v->tex_w != f->width || v->tex_h != f->height ||
        v->tex_fmt != fmt || v->tex_full_range != full_range) {
        if (v->tex)
            SDL_DestroyTexture(v->tex);
        SDL_SetYUVConversionMode(full_range ? SDL_YUV_CONVERSION_JPEG : SDL_YUV_CONVERSION_AUTOMATIC);
        v->tex = SDL_CreateTexture(v->ren, fmt, SDL_TEXTUREACCESS_STREAMING, f->width, f->height);
        if (!v->tex) {
            fprintf(stderr, "error: SDL_CreateTexture: %s\n", SDL_GetError());
            return -1;
        }
        v->tex_w = f->width;
        v->tex_h = f->height;
        v->tex_fmt = fmt;
        v->tex_full_range = full_range;
    }

    if (fmt == SDL_PIXELFORMAT_IYUV)
        return SDL_UpdateYUVTexture(v->tex, NULL, f->data[0], f->linesize[0],
                                    f->data[1], f->linesize[1], f->data[2], f->linesize[2]);
    return SDL_UpdateNVTexture(v->tex, NULL, f->data[0], f->linesize[0],
                               f->data[1], f->linesize[1]);
}

static SDL_Rect fit_rect(int ow, int oh, int fw, int fh)
{
    SDL_Rect r;
    if (fw <= 0 || fh <= 0) {
        r.x = r.y = 0;
        r.w = ow;
        r.h = oh;
        return r;
    }
    if ((int64_t)ow * fh > (int64_t)oh * fw) {
        r.h = oh;
        r.w = (int)((int64_t)oh * fw / fh);
    } else {
        r.w = ow;
        r.h = (int)((int64_t)ow * fh / fw);
    }
    r.x = (ow - r.w) / 2;
    r.y = (oh - r.h) / 2;
    return r;
}

typedef struct Stats {
    double fps_in, fps_shown, rx2dec_ms, dec2show_ms;
    int kbps, dropped, dec_errors, misfires, reorder;
} Stats;

static void render(View *v, const App *app, const Stats *st, const char *status,
                   const char *stream_info, int show_overlay)
{
    int ow = 0, oh = 0;

    SDL_GetRendererOutputSize(v->ren, &ow, &oh);
    SDL_SetRenderDrawColor(v->ren, 0, 0, 0, 255);
    SDL_RenderClear(v->ren);
    if (v->tex) {
        SDL_Rect dst = fit_rect(ow, oh, v->tex_w, v->tex_h);
        SDL_RenderCopy(v->ren, v->tex, NULL, &dst);
    }
    if (show_overlay) {
        char l[7][96];
        const char *lines[7];
        int n = 0, i;
        snprintf(l[n++], sizeof(l[0]), "%s", status);
        snprintf(l[n++], sizeof(l[0]), "%s %dX%d", stream_info, v->tex_w, v->tex_h);
        snprintf(l[n++], sizeof(l[0]), "FPS IN %.1f  SHOWN %.1f", st->fps_in, st->fps_shown);
        snprintf(l[n++], sizeof(l[0]), "DROPPED %d  DEC ERRORS %d", st->dropped, st->dec_errors);
        snprintf(l[n++], sizeof(l[0]), "RX>DEC %.1f MS  DEC>SHOW %.1f MS", st->rx2dec_ms, st->dec2show_ms);
        snprintf(l[n++], sizeof(l[0]), "%d KBIT/S  REORDER %d", st->kbps, st->reorder);
        if (app->opt.early)
            snprintf(l[n++], sizeof(l[0]), "EARLY MISFIRES %d", st->misfires);
        for (i = 0; i < n; i++)
            lines[i] = l[i];
        overlay_draw(v->ren, 8, 8, oh >= 1400 ? 3 : 2, lines, n);
    }
    SDL_RenderPresent(v->ren);
}

static void usage(void)
{
    fprintf(stderr,
        "usage: saigocam-receiver [options] rtsp://saigocam:<PASSWORD>@<PHONE_IP>:1935\n"
        "\n"
        "  --transport tcp|udp  RTSP transport (default tcp)\n"
        "  --hwdec NAME         hardware decode: d3d11va, dxva2, cuda, qsv, auto or none (default none)\n"
        "  --early              experimental: skip libavformat's H.264 parser (see README)\n"
        "  --vsync              wait for vertical sync when presenting (default off: lowest latency)\n"
        "  --renderer NAME      SDL render driver, e.g. direct3d11, direct3d, opengl\n"
        "  --fast               allow non-spec-compliant decoder speedups\n"
        "  --probe              probe the stream before playing (slower start)\n"
        "  --no-overlay         start with the stats overlay hidden\n"
        "  --fullscreen         start fullscreen\n"
        "  --verbose            more FFmpeg logging (credentials are still filtered)\n"
        "\n"
        "keys: O overlay, F fullscreen, Esc/Q quit\n");
}

static int parse_args(int argc, char **argv, Options *o)
{
    int i;
    memset(o, 0, sizeof(*o));
    o->transport = "tcp";
    o->overlay = 1;
    for (i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--transport") && i + 1 < argc) {
            o->transport = argv[++i];
            if (strcmp(o->transport, "tcp") && strcmp(o->transport, "udp")) {
                fprintf(stderr, "error: --transport must be tcp or udp\n");
                return -1;
            }
        } else if (!strcmp(a, "--hwdec") && i + 1 < argc) {
            o->hwdec = argv[++i];
        } else if (!strcmp(a, "--renderer") && i + 1 < argc) {
            o->renderer = argv[++i];
        } else if (!strcmp(a, "--early")) {
            o->early = 1;
        } else if (!strcmp(a, "--vsync")) {
            o->vsync = 1;
        } else if (!strcmp(a, "--fast")) {
            o->fast = 1;
        } else if (!strcmp(a, "--probe")) {
            o->probe = 1;
        } else if (!strcmp(a, "--no-overlay")) {
            o->overlay = 0;
        } else if (!strcmp(a, "--fullscreen")) {
            o->fullscreen = 1;
        } else if (!strcmp(a, "--verbose")) {
            o->verbose = 1;
        } else if (!strcmp(a, "-h") || !strcmp(a, "--help")) {
            return -1;
        } else if (a[0] == '-') {
            fprintf(stderr, "error: unknown option %s\n", a); /* never echo the URL */
            return -1;
        } else if (!o->url) {
            o->url = a;
        } else {
            fprintf(stderr, "error: more than one URL given\n");
            return -1;
        }
    }
    if (!o->url || (strncmp(o->url, "rtsp://", 7) && strncmp(o->url, "rtsps://", 8))) {
        fprintf(stderr, "error: an rtsp:// URL is required\n");
        return -1;
    }
    return 0;
}

int main(int argc, char **argv)
{
    static App app; /* zero-initialised */
    View v;
    SDL_Thread *thread;
    Uint32 last_stats, flags;
    int prev_decoded = 0, shown = 0, dec2show_n = 0, show_overlay;
    double dec2show_sum_ms = 0;
    Stats st;
    char status[96] = "", stream_info[96] = "";

    if (parse_args(argc, argv, &app.opt) < 0) {
        usage();
        return 2;
    }
    g_app = &app;
    prepare_url_secrets(&app);
    show_overlay = app.opt.overlay;
    memset(&v, 0, sizeof(v));
    memset(&st, 0, sizeof(st));

    av_log_set_level(app.opt.verbose ? AV_LOG_VERBOSE : AV_LOG_WARNING);
    av_log_set_callback(ff_log_cb);
    avformat_network_init();

    SDL_SetMainReady();
    if (SDL_Init(SDL_INIT_VIDEO) < 0) {
        fprintf(stderr, "error: SDL_Init: %s\n", SDL_GetError());
        return 1;
    }
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "linear");
    SDL_SetHint(SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0");
    if (app.opt.renderer)
        SDL_SetHint(SDL_HINT_RENDER_DRIVER, app.opt.renderer);

    flags = SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI;
    if (app.opt.fullscreen)
        flags |= SDL_WINDOW_FULLSCREEN_DESKTOP;
    v.win = SDL_CreateWindow("SaigoCam receiver", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                             960, 540, flags);
    if (v.win)
        v.ren = SDL_CreateRenderer(v.win, -1, SDL_RENDERER_ACCELERATED |
                                   (app.opt.vsync ? SDL_RENDERER_PRESENTVSYNC : 0));
    app.wake_event = SDL_RegisterEvents(1);
    app.sh.mtx = SDL_CreateMutex();
    app.sh.slot = av_frame_alloc();
    v.frame = av_frame_alloc();
    if (!v.win || !v.ren || app.wake_event == (Uint32)-1 || !app.sh.mtx || !app.sh.slot || !v.frame) {
        fprintf(stderr, "error: setup failed: %s\n", SDL_GetError());
        return 1;
    }
    {
        SDL_RendererInfo info;
        if (SDL_GetRendererInfo(v.ren, &info) == 0)
            fprintf(stderr, "renderer: %s, vsync %s\n", info.name, app.opt.vsync ? "on" : "off");
    }
    fprintf(stderr, "connecting to %s over %s%s\n", app.url_redacted, app.opt.transport,
            app.opt.early ? " (early mode)" : "");

    thread = SDL_CreateThread(decode_thread, "decode", &app);
    if (!thread) {
        fprintf(stderr, "error: SDL_CreateThread: %s\n", SDL_GetError());
        return 1;
    }

    last_stats = SDL_GetTicks();
    while (!SDL_AtomicGet(&app.quit)) {
        SDL_Event ev;
        int redraw = 0, got = 0;
        int64_t t_dec = 0;
        Uint32 now;

        if (SDL_WaitEventTimeout(&ev, 100)) {
            do {
                if (ev.type == SDL_QUIT) {
                    SDL_AtomicSet(&app.quit, 1);
                } else if (ev.type == SDL_KEYDOWN) {
                    SDL_Keycode k = ev.key.keysym.sym;
                    if (k == SDLK_ESCAPE || k == SDLK_q) {
                        SDL_AtomicSet(&app.quit, 1);
                    } else if (k == SDLK_o) {
                        show_overlay = !show_overlay;
                        redraw = 1;
                    } else if (k == SDLK_f) {
                        int fs = SDL_GetWindowFlags(v.win) & SDL_WINDOW_FULLSCREEN_DESKTOP;
                        SDL_SetWindowFullscreen(v.win, fs ? 0 : SDL_WINDOW_FULLSCREEN_DESKTOP);
                        redraw = 1;
                    }
                } else if (ev.type == SDL_WINDOWEVENT) {
                    redraw = 1;
                }
            } while (SDL_PollEvent(&ev));
        }

        /* Take the newest frame, if any. */
        SDL_AtomicSet(&app.wake_pending, 0);
        SDL_LockMutex(app.sh.mtx);
        if (app.sh.slot_full) {
            av_frame_unref(v.frame);
            av_frame_move_ref(v.frame, app.sh.slot);
            app.sh.slot_full = 0;
            t_dec = app.sh.slot_t_dec;
            got = 1;
        }
        SDL_UnlockMutex(app.sh.mtx);
        if (got) {
            if (upload_frame(&v, v.frame) < 0)
                got = 0;
            else
                redraw = 1;
        }

        now = SDL_GetTicks();
        if (now - last_stats >= 1000) {
            double secs = (now - last_stats) / 1000.0;
            int decoded = SDL_AtomicGet(&app.n_decoded);
            int bytes = SDL_AtomicSet(&app.bytes, 0);

            SDL_LockMutex(app.sh.mtx);
            st.rx2dec_ms = app.sh.rx2dec_n ? app.sh.rx2dec_sum_us / 1000.0 / app.sh.rx2dec_n : 0;
            app.sh.rx2dec_sum_us = 0;
            app.sh.rx2dec_n = 0;
            snprintf(status, sizeof(status), "%s", app.sh.status);
            SDL_UnlockMutex(app.sh.mtx);

            st.fps_in = (decoded - prev_decoded) / secs;
            st.fps_shown = shown / secs;
            st.dec2show_ms = dec2show_n ? dec2show_sum_ms / dec2show_n : 0;
            st.kbps = (int)(bytes * 8.0 / 1000.0 / secs);
            st.dropped = SDL_AtomicGet(&app.n_dropped);
            st.dec_errors = SDL_AtomicGet(&app.n_dec_errors);
            st.misfires = SDL_AtomicGet(&app.n_misfires);
            st.reorder = SDL_AtomicGet(&app.reorder);
            prev_decoded = decoded;
            shown = 0;
            dec2show_n = 0;
            dec2show_sum_ms = 0;
            last_stats = now;

            fprintf(stderr, "[stats] %s | in %.1f fps, shown %.1f fps, dropped %d, dec errors %d, "
                    "rx>dec %.1f ms, dec>show %.1f ms, %d kbit/s, reorder %d%s\n",
                    status, st.fps_in, st.fps_shown, st.dropped, st.dec_errors, st.rx2dec_ms,
                    st.dec2show_ms, st.kbps, st.reorder, app.opt.early ? ", early" : "");
            {
                char title[160];
                snprintf(title, sizeof(title), "SaigoCam receiver - %.1f fps, dropped %d",
                         st.fps_shown, st.dropped);
                SDL_SetWindowTitle(v.win, title);
            }
            redraw = 1;
        }

        if (redraw) {
            SDL_LockMutex(app.sh.mtx);
            snprintf(status, sizeof(status), "%s", app.sh.status);
            snprintf(stream_info, sizeof(stream_info), "%s", app.sh.stream_info);
            SDL_UnlockMutex(app.sh.mtx);
            render(&v, &app, &st, status, stream_info, show_overlay);
            if (got) {
                dec2show_sum_ms += (av_gettime_relative() - t_dec) / 1000.0;
                dec2show_n++;
                shown++;
            }
        }
    }

    SDL_WaitThread(thread, NULL);
    if (v.tex)
        SDL_DestroyTexture(v.tex);
    SDL_DestroyRenderer(v.ren);
    SDL_DestroyWindow(v.win);
    av_frame_free(&v.frame);
    av_frame_free(&app.sh.slot);
    SDL_DestroyMutex(app.sh.mtx);
    SDL_Quit();
    avformat_network_deinit();
    return 0;
}
