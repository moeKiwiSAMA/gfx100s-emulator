#define _GNU_SOURCE

#include "fuji_audio.h"

#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

/* Minimal ABI-compatible PulseAudio/PipeWire C types (no compile-time libpulse-dev linkage!) */
typedef struct pa_simple pa_simple;
typedef struct pa_threaded_mainloop pa_threaded_mainloop;
typedef struct pa_mainloop_api pa_mainloop_api;
typedef struct pa_context pa_context;
typedef struct pa_operation pa_operation;
typedef struct pa_stream pa_stream;
typedef struct pa_channel_map pa_channel_map;
typedef struct pa_proplist pa_proplist;

typedef enum pa_sample_format {
    PA_SAMPLE_S16LE = 3
} pa_sample_format_t;

typedef enum pa_stream_direction {
    PA_STREAM_PLAYBACK = 1,
    PA_STREAM_RECORD = 2
} pa_stream_direction_t;

typedef enum pa_context_state {
    PA_CONTEXT_UNCONNECTED = 0,
    PA_CONTEXT_CONNECTING = 1,
    PA_CONTEXT_AUTHORIZING = 2,
    PA_CONTEXT_SETTING_NAME = 3,
    PA_CONTEXT_READY = 4,
    PA_CONTEXT_FAILED = 5,
    PA_CONTEXT_TERMINATED = 6
} pa_context_state_t;

typedef enum pa_stream_state {
    PA_STREAM_UNCONNECTED = 0,
    PA_STREAM_CREATING = 1,
    PA_STREAM_READY = 2,
    PA_STREAM_FAILED = 3,
    PA_STREAM_TERMINATED = 4
} pa_stream_state_t;

typedef enum pa_stream_flags {
    PA_STREAM_NOFLAGS = 0x0000u,
    PA_STREAM_ADJUST_LATENCY = 0x2000u
} pa_stream_flags_t;

typedef enum pa_context_flags {
    PA_CONTEXT_NOFLAGS = 0x0000u
} pa_context_flags_t;

typedef struct pa_sample_spec {
    pa_sample_format_t format;
    uint32_t rate;
    uint8_t channels;
} pa_sample_spec;

typedef struct pa_buffer_attr {
    uint32_t maxlength;
    uint32_t tlength;
    uint32_t prebuf;
    uint32_t minreq;
    uint32_t fragsize;
} pa_buffer_attr;

typedef struct pa_server_info {
    const char* user_name;
    const char* host_name;
    const char* server_version;
    const char* server_name;
    pa_sample_spec sample_spec;
    const char* default_sink_name;
    const char* default_source_name;
    uint32_t cookie;
    /* remaining fields unused */
} pa_server_info;

/* Prefix layout of pa_sink_info / pa_source_info matches libpulse ABI v0.9.x..17.x */
typedef struct pa_channel_map_stub {
    uint8_t channels;
    int map[32];
} pa_channel_map_stub;

typedef struct pa_cvolume_stub {
    uint8_t channels;
    uint32_t values[32];
} pa_cvolume_stub;

typedef struct pa_sink_info {
    const char* name;
    uint32_t index;
    const char* description;
    pa_sample_spec sample_spec;
    pa_channel_map_stub channel_map;
    uint32_t owner_module;
    pa_cvolume_stub volume;
    int mute;
    uint32_t monitor_source;
    const char* monitor_source_name;
} pa_sink_info;

typedef struct pa_source_info {
    const char* name;
    uint32_t index;
    const char* description;
    pa_sample_spec sample_spec;
    pa_channel_map_stub channel_map;
    uint32_t owner_module;
    pa_cvolume_stub volume;
    int mute;
    uint32_t monitor_of_sink;
    const char* monitor_of_sink_name;
} pa_source_info;

typedef void (*pa_context_notify_cb_t)(pa_context* c, void* userdata);
typedef void (*pa_server_info_cb_t)(pa_context* c, const pa_server_info* i, void* userdata);
typedef void (*pa_sink_info_cb_t)(pa_context* c, const pa_sink_info* i, int eol, void* userdata);
typedef void (*pa_source_info_cb_t)(pa_context* c, const pa_source_info* i, int eol, void* userdata);
typedef void (*pa_stream_notify_cb_t)(pa_stream* p, void* userdata);
typedef void (*pa_stream_request_cb_t)(pa_stream* p, size_t nbytes, void* userdata);

typedef struct PulseApi {
    int loaded;
    int available;
    void* lib_pulse;
    void* lib_simple;

    pa_threaded_mainloop* (*mainloop_new)(void);
    void (*mainloop_free)(pa_threaded_mainloop*);
    int (*mainloop_start)(pa_threaded_mainloop*);
    void (*mainloop_stop)(pa_threaded_mainloop*);
    void (*mainloop_lock)(pa_threaded_mainloop*);
    void (*mainloop_unlock)(pa_threaded_mainloop*);
    void (*mainloop_wait)(pa_threaded_mainloop*);
    void (*mainloop_signal)(pa_threaded_mainloop*, int);
    pa_mainloop_api* (*mainloop_get_api)(pa_threaded_mainloop*);

    pa_context* (*context_new)(pa_mainloop_api*, const char*);
    void (*context_unref)(pa_context*);
    int (*context_connect)(pa_context*, const char*, pa_context_flags_t, const void*);
    void (*context_disconnect)(pa_context*);
    pa_context_state_t (*context_get_state)(const pa_context*);
    void (*context_set_state_callback)(pa_context*, pa_context_notify_cb_t, void*);
    pa_operation* (*context_get_server_info)(pa_context*, pa_server_info_cb_t, void*);
    pa_operation* (*context_get_sink_info_list)(pa_context*, pa_sink_info_cb_t, void*);
    pa_operation* (*context_get_source_info_list)(pa_context*, pa_source_info_cb_t, void*);
    void (*operation_unref)(pa_operation*);

    pa_stream* (*stream_new)(pa_context*, const char*, const pa_sample_spec*, const pa_channel_map*);
    void (*stream_unref)(pa_stream*);
    void (*stream_set_state_callback)(pa_stream*, pa_stream_notify_cb_t, void*);
    void (*stream_set_read_callback)(pa_stream*, pa_stream_request_cb_t, void*);
    int (*stream_connect_record)(pa_stream*, const char*, const pa_buffer_attr*, pa_stream_flags_t);
    int (*stream_disconnect)(pa_stream*);
    pa_stream_state_t (*stream_get_state)(const pa_stream*);
    int (*stream_peek)(pa_stream*, const void**, size_t*);
    int (*stream_drop)(pa_stream*);

    pa_simple* (*simple_new)(const char*, const char*, pa_stream_direction_t, const char*,
                             const char*, const pa_sample_spec*, const pa_channel_map*,
                             const pa_buffer_attr*, int*);
    void (*simple_free)(pa_simple*);
    int (*simple_write)(pa_simple*, const void*, size_t, int*);
    uint64_t (*simple_get_latency)(pa_simple*, int*);
} PulseApi;

static PulseApi g_pa = {0};
static pthread_mutex_t g_pa_init_lock = PTHREAD_MUTEX_INITIALIZER;

static int load_pulse_api(void) {
    pthread_mutex_lock(&g_pa_init_lock);
    if (g_pa.loaded) {
        int ok = g_pa.available;
        pthread_mutex_unlock(&g_pa_init_lock);
        return ok;
    }
    g_pa.loaded = 1;
    g_pa.available = 0;

    g_pa.lib_pulse = dlopen("libpulse.so.0", RTLD_LAZY | RTLD_LOCAL);
    g_pa.lib_simple = dlopen("libpulse-simple.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!g_pa.lib_pulse || !g_pa.lib_simple) {
        pthread_mutex_unlock(&g_pa_init_lock);
        return 0;
    }

#define LOAD_SYM(lib, field, sym)                                              \
    do {                                                                       \
        *(void**)(&g_pa.field) = dlsym((lib), (sym));                          \
        if (!g_pa.field) {                                                     \
            pthread_mutex_unlock(&g_pa_init_lock);                             \
            return 0;                                                          \
        }                                                                      \
    } while (0)

    LOAD_SYM(g_pa.lib_pulse, mainloop_new, "pa_threaded_mainloop_new");
    LOAD_SYM(g_pa.lib_pulse, mainloop_free, "pa_threaded_mainloop_free");
    LOAD_SYM(g_pa.lib_pulse, mainloop_start, "pa_threaded_mainloop_start");
    LOAD_SYM(g_pa.lib_pulse, mainloop_stop, "pa_threaded_mainloop_stop");
    LOAD_SYM(g_pa.lib_pulse, mainloop_lock, "pa_threaded_mainloop_lock");
    LOAD_SYM(g_pa.lib_pulse, mainloop_unlock, "pa_threaded_mainloop_unlock");
    LOAD_SYM(g_pa.lib_pulse, mainloop_wait, "pa_threaded_mainloop_wait");
    LOAD_SYM(g_pa.lib_pulse, mainloop_signal, "pa_threaded_mainloop_signal");
    LOAD_SYM(g_pa.lib_pulse, mainloop_get_api, "pa_threaded_mainloop_get_api");

    LOAD_SYM(g_pa.lib_pulse, context_new, "pa_context_new");
    LOAD_SYM(g_pa.lib_pulse, context_unref, "pa_context_unref");
    LOAD_SYM(g_pa.lib_pulse, context_connect, "pa_context_connect");
    LOAD_SYM(g_pa.lib_pulse, context_disconnect, "pa_context_disconnect");
    LOAD_SYM(g_pa.lib_pulse, context_get_state, "pa_context_get_state");
    LOAD_SYM(g_pa.lib_pulse, context_set_state_callback, "pa_context_set_state_callback");
    LOAD_SYM(g_pa.lib_pulse, context_get_server_info, "pa_context_get_server_info");
    LOAD_SYM(g_pa.lib_pulse, context_get_sink_info_list, "pa_context_get_sink_info_list");
    LOAD_SYM(g_pa.lib_pulse, context_get_source_info_list, "pa_context_get_source_info_list");
    LOAD_SYM(g_pa.lib_pulse, operation_unref, "pa_operation_unref");

    LOAD_SYM(g_pa.lib_pulse, stream_new, "pa_stream_new");
    LOAD_SYM(g_pa.lib_pulse, stream_unref, "pa_stream_unref");
    LOAD_SYM(g_pa.lib_pulse, stream_set_state_callback, "pa_stream_set_state_callback");
    LOAD_SYM(g_pa.lib_pulse, stream_set_read_callback, "pa_stream_set_read_callback");
    LOAD_SYM(g_pa.lib_pulse, stream_connect_record, "pa_stream_connect_record");
    LOAD_SYM(g_pa.lib_pulse, stream_disconnect, "pa_stream_disconnect");
    LOAD_SYM(g_pa.lib_pulse, stream_get_state, "pa_stream_get_state");
    LOAD_SYM(g_pa.lib_pulse, stream_peek, "pa_stream_peek");
    LOAD_SYM(g_pa.lib_pulse, stream_drop, "pa_stream_drop");

    LOAD_SYM(g_pa.lib_simple, simple_new, "pa_simple_new");
    LOAD_SYM(g_pa.lib_simple, simple_free, "pa_simple_free");
    LOAD_SYM(g_pa.lib_simple, simple_write, "pa_simple_write");
    LOAD_SYM(g_pa.lib_simple, simple_get_latency, "pa_simple_get_latency");
#undef LOAD_SYM

    g_pa.available = 1;
    pthread_mutex_unlock(&g_pa_init_lock);
    return 1;
}

static void ctx_state_cb(pa_context* c, void* userdata) {
    (void)c;
    pa_threaded_mainloop* ml = (pa_threaded_mainloop*)userdata;
    g_pa.mainloop_signal(ml, 0);
}

typedef struct EnumCtx {
    pa_threaded_mainloop* ml;
    int32_t direction;
    FujiAudioDeviceInfo* out;
    int32_t max_count;
    int32_t count;
    int done;
    char default_sink[256];
    char default_source[256];
} EnumCtx;

static void safe_copy(char* dst, size_t cap, const char* src) {
    if (!dst || cap == 0) return;
    if (!src) { dst[0] = '\0'; return; }
    snprintf(dst, cap, "%s", src);
}

static void server_info_cb(pa_context* c, const pa_server_info* info, void* userdata) {
    (void)c;
    EnumCtx* e = (EnumCtx*)userdata;
    if (info) {
        safe_copy(e->default_sink, sizeof(e->default_sink), info->default_sink_name);
        safe_copy(e->default_source, sizeof(e->default_source), info->default_source_name);
    }
    e->done = 1;
    g_pa.mainloop_signal(e->ml, 0);
}

static void sink_info_cb(pa_context* c, const pa_sink_info* info, int eol, void* userdata) {
    (void)c;
    EnumCtx* e = (EnumCtx*)userdata;
    if (eol || !info) {
        e->done = 1;
        g_pa.mainloop_signal(e->ml, 0);
        return;
    }
    if (e->count < e->max_count) {
        FujiAudioDeviceInfo d;
        memset(&d, 0, sizeof(d));
        if (e->direction == FUJI_AUDIO_DIR_LOOPBACK) {
            if (info->monitor_source_name && info->monitor_source_name[0] != '\0') {
                safe_copy(d.id, sizeof(d.id), info->monitor_source_name);
            } else if (info->name) {
                snprintf(d.id, sizeof(d.id), "%s.monitor", info->name);
            }
        } else {
            safe_copy(d.id, sizeof(d.id), info->name);
        }
        safe_copy(d.name, sizeof(d.name),
                  (info->description && info->description[0]) ? info->description : info->name);
        d.is_default = (info->name && e->default_sink[0] && strcmp(info->name, e->default_sink) == 0) ? 1 : 0;
        d.sample_rate_hz = (info->sample_spec.rate >= 8000) ? (int32_t)info->sample_spec.rate : 48000;
        d.channels = (info->sample_spec.channels >= 1) ? (int32_t)info->sample_spec.channels : 2;
        e->out[e->count++] = d;
    }
}

static void source_info_cb(pa_context* c, const pa_source_info* info, int eol, void* userdata) {
    (void)c;
    EnumCtx* e = (EnumCtx*)userdata;
    if (eol || !info) {
        e->done = 1;
        g_pa.mainloop_signal(e->ml, 0);
        return;
    }
    /* Skip sink monitors when enumerating microphone inputs (monitors belong in LOOPBACK) */
    if (info->monitor_of_sink != 0xFFFFFFFFu) {
        return;
    }
    if (e->count < e->max_count) {
        FujiAudioDeviceInfo d;
        memset(&d, 0, sizeof(d));
        safe_copy(d.id, sizeof(d.id), info->name);
        safe_copy(d.name, sizeof(d.name),
                  (info->description && info->description[0]) ? info->description : info->name);
        d.is_default = (info->name && e->default_source[0] && strcmp(info->name, e->default_source) == 0) ? 1 : 0;
        d.sample_rate_hz = (info->sample_spec.rate >= 8000) ? (int32_t)info->sample_spec.rate : 48000;
        d.channels = (info->sample_spec.channels >= 1) ? (int32_t)info->sample_spec.channels : 2;
        e->out[e->count++] = d;
    }
}

#define FUJI_AUDIO_MAGIC 0x46554A41u

typedef struct FujiLinuxAudioStream {
    uint32_t magic;
    int32_t is_input;
    int32_t sample_rate_hz;
    int32_t channels;
    int32_t latency_ms;

    /* Output uses pa_simple */
    pa_simple* simple;

    /* Input uses pa_threaded_mainloop + pa_stream + timed ring buffer */
    pa_threaded_mainloop* ml;
    pa_context* ctx;
    pa_stream* stream;
    pthread_mutex_t ring_lock;
    pthread_cond_t ring_cond;
    int16_t* ring_buf;
    int32_t ring_cap_frames;
    int32_t ring_head_frames;
    int32_t ring_avail_frames;
    int32_t stream_failed;
} FujiLinuxAudioStream;

static void stream_state_cb(pa_stream* p, void* userdata) {
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)userdata;
    pa_stream_state_t st = g_pa.stream_get_state(p);
    if (st == PA_STREAM_FAILED || st == PA_STREAM_TERMINATED) {
        pthread_mutex_lock(&s->ring_lock);
        s->stream_failed = 1;
        pthread_cond_broadcast(&s->ring_cond);
        pthread_mutex_unlock(&s->ring_lock);
    }
    g_pa.mainloop_signal(s->ml, 0);
}

static void stream_read_cb(pa_stream* p, size_t nbytes, void* userdata) {
    (void)nbytes;
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)userdata;
    const size_t frame_bytes = (size_t)s->channels * sizeof(int16_t);
    for (;;) {
        const void* data = NULL;
        size_t len = 0;
        if (g_pa.stream_peek(p, &data, &len) < 0 || len == 0) break;
        int32_t frames = (int32_t)(len / frame_bytes);
        if (frames > 0) {
            pthread_mutex_lock(&s->ring_lock);
            int32_t ch = s->channels;
            for (int32_t i = 0; i < frames; ++i) {
                int32_t wp;
                if (s->ring_avail_frames < s->ring_cap_frames) {
                    wp = (s->ring_head_frames + s->ring_avail_frames) % s->ring_cap_frames;
                    s->ring_avail_frames++;
                } else {
                    wp = s->ring_head_frames;
                    s->ring_head_frames = (s->ring_head_frames + 1) % s->ring_cap_frames;
                }
                int16_t* dst = s->ring_buf + (size_t)wp * (size_t)ch;
                if (!data) {
                    for (int32_t c = 0; c < ch; ++c) dst[c] = 0;
                } else {
                    const int16_t* src = ((const int16_t*)data) + (size_t)i * (size_t)ch;
                    for (int32_t c = 0; c < ch; ++c) dst[c] = src[c];
                }
            }
            pthread_cond_signal(&s->ring_cond);
            pthread_mutex_unlock(&s->ring_lock);
        }
        g_pa.stream_drop(p);
    }
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_abi_version(void) {
    return FUJI_AUDIO_ABI_VERSION;
}

FUJI_AUDIO_EXPORT const char* fuji_audio_backend_name(void) {
    return "pulse-pipewire";
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_enumerate(int32_t direction, FujiAudioDeviceInfo* out, int32_t max_count) {
    if (direction < FUJI_AUDIO_DIR_OUTPUT || direction > FUJI_AUDIO_DIR_LOOPBACK) return -1;
    if (max_count <= 0 || !out) return 0;
    if (!load_pulse_api()) return 0;

    pa_threaded_mainloop* ml = g_pa.mainloop_new();
    if (!ml) return 0;
    pa_context* ctx = g_pa.context_new(g_pa.mainloop_get_api(ml), "Fuji GFX100S Audio Enum");
    if (!ctx) {
        g_pa.mainloop_free(ml);
        return 0;
    }

    g_pa.context_set_state_callback(ctx, ctx_state_cb, ml);
    if (g_pa.mainloop_start(ml) < 0) {
        g_pa.context_unref(ctx);
        g_pa.mainloop_free(ml);
        return 0;
    }

    g_pa.mainloop_lock(ml);
    int connected = 0;
    if (g_pa.context_connect(ctx, NULL, PA_CONTEXT_NOFLAGS, NULL) >= 0) {
        for (;;) {
            pa_context_state_t st = g_pa.context_get_state(ctx);
            if (st == PA_CONTEXT_READY) { connected = 1; break; }
            if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) break;
            g_pa.mainloop_wait(ml);
        }
    }

    EnumCtx e;
    memset(&e, 0, sizeof(e));
    e.ml = ml;
    e.direction = direction;
    e.out = out;
    e.max_count = max_count;

    if (connected) {
        pa_operation* op = g_pa.context_get_server_info(ctx, server_info_cb, &e);
        if (op) {
            while (!e.done && g_pa.context_get_state(ctx) == PA_CONTEXT_READY) {
                g_pa.mainloop_wait(ml);
            }
            g_pa.operation_unref(op);
        }

        e.done = 0;
        pa_operation* list_op = (direction == FUJI_AUDIO_DIR_INPUT)
            ? g_pa.context_get_source_info_list(ctx, source_info_cb, &e)
            : g_pa.context_get_sink_info_list(ctx, sink_info_cb, &e);
        if (list_op) {
            while (!e.done && g_pa.context_get_state(ctx) == PA_CONTEXT_READY) {
                g_pa.mainloop_wait(ml);
            }
            g_pa.operation_unref(list_op);
        }
        g_pa.context_disconnect(ctx);
    }

    g_pa.mainloop_unlock(ml);
    g_pa.mainloop_stop(ml);
    g_pa.context_unref(ctx);
    g_pa.mainloop_free(ml);
    return e.count;
}

FUJI_AUDIO_EXPORT void* fuji_audio_open_output(const char* device_id, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms) {
    if (sample_rate_hz < 8000 || sample_rate_hz > 192000) return NULL;
    if (channels < 1 || channels > 2) return NULL;
    if (!load_pulse_api()) return NULL;
    if (target_latency_ms < 5) target_latency_ms = 5;
    if (target_latency_ms > 500) target_latency_ms = 500;

    pa_sample_spec ss;
    ss.format = PA_SAMPLE_S16LE;
    ss.rate = (uint32_t)sample_rate_hz;
    ss.channels = (uint8_t)channels;

    uint32_t frame_bytes = (uint32_t)(channels * 2);
    uint32_t tlength = (uint32_t)(((uint64_t)target_latency_ms * (uint64_t)sample_rate_hz) / 1000ull) * frame_bytes;
    if (tlength < frame_bytes * 128u) tlength = frame_bytes * 128u;

    pa_buffer_attr ba;
    ba.maxlength = tlength * 2u;
    ba.tlength = tlength;
    ba.prebuf = tlength / 2u;
    ba.minreq = tlength / 4u;
    ba.fragsize = (uint32_t)-1;

    const char* dev = (device_id && device_id[0] != '\0') ? device_id : NULL;
    int err = 0;
    pa_simple* simple = g_pa.simple_new(NULL, "Fuji GFX100S Emulator", PA_STREAM_PLAYBACK,
                                        dev, "Camera Audio Output", &ss, NULL, &ba, &err);
    if (!simple) return NULL;

    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)calloc(1, sizeof(FujiLinuxAudioStream));
    if (!s) {
        g_pa.simple_free(simple);
        return NULL;
    }
    s->magic = FUJI_AUDIO_MAGIC;
    s->is_input = 0;
    s->sample_rate_hz = sample_rate_hz;
    s->channels = channels;
    s->latency_ms = target_latency_ms;
    s->simple = simple;
    return s;
}

FUJI_AUDIO_EXPORT void* fuji_audio_open_input(const char* device_id, int32_t loopback, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms) {
    if (sample_rate_hz < 8000 || sample_rate_hz > 192000) return NULL;
    if (channels < 1 || channels > 2) return NULL;
    if (!load_pulse_api()) return NULL;
    if (target_latency_ms < 5) target_latency_ms = 5;
    if (target_latency_ms > 500) target_latency_ms = 500;

    char resolved_dev[300];
    const char* target_dev = NULL;
    if (loopback != 0) {
        if (device_id && device_id[0] != '\0') {
            size_t len = strlen(device_id);
            if (len >= 8 && strcmp(device_id + len - 8, ".monitor") == 0) {
                safe_copy(resolved_dev, sizeof(resolved_dev), device_id);
            } else {
                snprintf(resolved_dev, sizeof(resolved_dev), "%s.monitor", device_id);
            }
            target_dev = resolved_dev;
        } else {
            target_dev = "@DEFAULT_MONITOR@";
        }
    } else if (device_id && device_id[0] != '\0') {
        target_dev = device_id;
    }

    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)calloc(1, sizeof(FujiLinuxAudioStream));
    if (!s) return NULL;
    s->magic = FUJI_AUDIO_MAGIC;
    s->is_input = 1;
    s->sample_rate_hz = sample_rate_hz;
    s->channels = channels;
    s->latency_ms = target_latency_ms;
    pthread_mutex_init(&s->ring_lock, NULL);
    pthread_cond_init(&s->ring_cond, NULL);

    s->ring_cap_frames = (sample_rate_hz * 500) / 1000;
    if (s->ring_cap_frames < 8192) s->ring_cap_frames = 8192;
    s->ring_buf = (int16_t*)calloc((size_t)s->ring_cap_frames * (size_t)channels, sizeof(int16_t));
    if (!s->ring_buf) {
        pthread_cond_destroy(&s->ring_cond);
        pthread_mutex_destroy(&s->ring_lock);
        free(s);
        return NULL;
    }

    s->ml = g_pa.mainloop_new();
    if (!s->ml) goto fail;
    s->ctx = g_pa.context_new(g_pa.mainloop_get_api(s->ml), "Fuji GFX100S Audio Input");
    if (!s->ctx) goto fail;
    g_pa.context_set_state_callback(s->ctx, ctx_state_cb, s->ml);
    if (g_pa.mainloop_start(s->ml) < 0) goto fail;

    g_pa.mainloop_lock(s->ml);
    if (g_pa.context_connect(s->ctx, NULL, PA_CONTEXT_NOFLAGS, NULL) < 0) {
        g_pa.mainloop_unlock(s->ml);
        goto fail_stop;
    }
    for (;;) {
        pa_context_state_t st = g_pa.context_get_state(s->ctx);
        if (st == PA_CONTEXT_READY) break;
        if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) {
            g_pa.mainloop_unlock(s->ml);
            goto fail_stop;
        }
        g_pa.mainloop_wait(s->ml);
    }

    pa_sample_spec ss;
    ss.format = PA_SAMPLE_S16LE;
    ss.rate = (uint32_t)sample_rate_hz;
    ss.channels = (uint8_t)channels;

    s->stream = g_pa.stream_new(s->ctx, loopback ? "System Loopback Capture" : "Microphone Capture", &ss, NULL);
    if (!s->stream) {
        g_pa.mainloop_unlock(s->ml);
        goto fail_stop;
    }
    g_pa.stream_set_state_callback(s->stream, stream_state_cb, s);
    g_pa.stream_set_read_callback(s->stream, stream_read_cb, s);

    uint32_t frame_bytes = (uint32_t)(channels * 2);
    uint32_t frag = (uint32_t)(((uint64_t)target_latency_ms * (uint64_t)sample_rate_hz) / 1000ull) * frame_bytes;
    if (frag < frame_bytes * 64u) frag = frame_bytes * 64u;

    pa_buffer_attr ba;
    ba.maxlength = (uint32_t)-1;
    ba.tlength = (uint32_t)-1;
    ba.prebuf = (uint32_t)-1;
    ba.minreq = (uint32_t)-1;
    ba.fragsize = frag;

    if (g_pa.stream_connect_record(s->stream, target_dev, &ba, PA_STREAM_ADJUST_LATENCY) < 0) {
        g_pa.mainloop_unlock(s->ml);
        goto fail_stop;
    }
    for (;;) {
        pa_stream_state_t st = g_pa.stream_get_state(s->stream);
        if (st == PA_STREAM_READY) break;
        if (st == PA_STREAM_FAILED || st == PA_STREAM_TERMINATED) {
            g_pa.mainloop_unlock(s->ml);
            goto fail_stop;
        }
        g_pa.mainloop_wait(s->ml);
    }
    g_pa.mainloop_unlock(s->ml);
    return s;

fail_stop:
    g_pa.mainloop_lock(s->ml);
    if (s->stream) {
        g_pa.stream_disconnect(s->stream);
        g_pa.stream_unref(s->stream);
        s->stream = NULL;
    }
    if (s->ctx) {
        g_pa.context_disconnect(s->ctx);
        g_pa.context_unref(s->ctx);
        s->ctx = NULL;
    }
    g_pa.mainloop_unlock(s->ml);
    g_pa.mainloop_stop(s->ml);
fail:
    if (s->ctx) g_pa.context_unref(s->ctx);
    if (s->ml) g_pa.mainloop_free(s->ml);
    free(s->ring_buf);
    pthread_cond_destroy(&s->ring_cond);
    pthread_mutex_destroy(&s->ring_lock);
    s->magic = 0;
    free(s);
    return NULL;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_write(void* handle, const int16_t* interleaved_pcm, int32_t frames) {
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC || s->is_input || !s->simple) return -1;
    if (frames <= 0) return 0;
    if (!interleaved_pcm) return -1;
    int err = 0;
    size_t bytes = (size_t)frames * (size_t)s->channels * sizeof(int16_t);
    if (g_pa.simple_write(s->simple, interleaved_pcm, bytes, &err) < 0) {
        return -2;
    }
    return frames;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_read(void* handle, int16_t* interleaved_pcm, int32_t max_frames, int32_t timeout_ms) {
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC || !s->is_input || !s->stream) return -1;
    if (max_frames <= 0) return 0;
    if (!interleaved_pcm) return -1;
    if (timeout_ms < 0) timeout_ms = 0;

    pthread_mutex_lock(&s->ring_lock);
    if (s->ring_avail_frames == 0 && !s->stream_failed && timeout_ms > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        uint64_t add_ns = (uint64_t)timeout_ms * 1000000ull;
        ts.tv_sec += (time_t)(add_ns / 1000000000ull);
        ts.tv_nsec += (long)(add_ns % 1000000000ull);
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec += 1;
            ts.tv_nsec -= 1000000000L;
        }
        while (s->ring_avail_frames == 0 && !s->stream_failed) {
            int rc = pthread_cond_timedwait(&s->ring_cond, &s->ring_lock, &ts);
            if (rc == ETIMEDOUT) break;
        }
    }

    if (s->ring_avail_frames == 0 && s->stream_failed) {
        pthread_mutex_unlock(&s->ring_lock);
        return -2;
    }

    int32_t to_read = (s->ring_avail_frames < max_frames) ? s->ring_avail_frames : max_frames;
    int32_t ch = s->channels;
    for (int32_t i = 0; i < to_read; ++i) {
        const int16_t* src = s->ring_buf + (size_t)s->ring_head_frames * (size_t)ch;
        int16_t* dst = interleaved_pcm + (size_t)i * (size_t)ch;
        for (int32_t c = 0; c < ch; ++c) dst[c] = src[c];
        s->ring_head_frames = (s->ring_head_frames + 1) % s->ring_cap_frames;
        s->ring_avail_frames--;
    }
    pthread_mutex_unlock(&s->ring_lock);
    return to_read;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_get_latency_ms(void* handle) {
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC) return -1;
    if (!s->is_input && s->simple) {
        int err = 0;
        uint64_t us = g_pa.simple_get_latency(s->simple, &err);
        if (err == 0 && us > 0 && us < 5000000ull) {
            int32_t ms = (int32_t)((us + 500ull) / 1000ull);
            return ms > 0 ? ms : 1;
        }
    }
    return s->latency_ms;
}

FUJI_AUDIO_EXPORT void fuji_audio_close(void* handle) {
    FujiLinuxAudioStream* s = (FujiLinuxAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC) return;
    if (s->simple) {
        g_pa.simple_free(s->simple);
        s->simple = NULL;
    }
    if (s->ml) {
        g_pa.mainloop_lock(s->ml);
        if (s->stream) {
            g_pa.stream_set_read_callback(s->stream, NULL, NULL);
            g_pa.stream_set_state_callback(s->stream, NULL, NULL);
            g_pa.stream_disconnect(s->stream);
            g_pa.stream_unref(s->stream);
            s->stream = NULL;
        }
        if (s->ctx) {
            g_pa.context_set_state_callback(s->ctx, NULL, NULL);
            g_pa.context_disconnect(s->ctx);
            g_pa.context_unref(s->ctx);
            s->ctx = NULL;
        }
        g_pa.mainloop_unlock(s->ml);
        g_pa.mainloop_stop(s->ml);
        g_pa.mainloop_free(s->ml);
        s->ml = NULL;
    }
    if (s->is_input) {
        free(s->ring_buf);
        pthread_cond_destroy(&s->ring_cond);
        pthread_mutex_destroy(&s->ring_lock);
    }
    s->magic = 0;
    free(s);
}

/* --------------------------------------------------------------------------
 * Linux OS-Native High-Resolution Timer, Timerslack & THP Helpers (v1)
 * -------------------------------------------------------------------------- */
#define FUJI_OS_WAITER_MAGIC 0x464F5357u

typedef struct FujiLinuxOsWaiter {
    uint32_t magic;
    int efd;
} FujiLinuxOsWaiter;

static _Thread_local int g_tls_timerslack_applied = 0;

static int apply_linux_timerslack_1ns(void) {
#ifdef PR_SET_TIMERSLACK
    if (g_tls_timerslack_applied) return 1;
    if (prctl(PR_SET_TIMERSLACK, 1UL, 0UL, 0UL, 0UL) == 0) {
        g_tls_timerslack_applied = 1;
        return 1;
    }
#endif
    return 0;
}

static int64_t linux_monotonic_ns_now(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_abi_version(void) {
    return FUJI_OS_ABI_VERSION;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_capabilities(void) {
    int32_t caps = FUJI_OS_CAP_HIGHRES_TIMER;
#ifdef PR_SET_TIMERSLACK
    caps |= FUJI_OS_CAP_TIMER_PERIOD_1MS | FUJI_OS_CAP_THREAD_PRIORITY;
#endif
#ifdef MADV_HUGEPAGE
    caps |= FUJI_OS_CAP_MADV_HUGEPAGE;
#endif
    return caps;
}

FUJI_AUDIO_EXPORT int64_t fuji_os_monotonic_nanos(void) {
    return linux_monotonic_ns_now();
}

FUJI_AUDIO_EXPORT int32_t fuji_os_tune_process(int32_t enable_high_perf) {
    int32_t applied = FUJI_OS_CAP_HIGHRES_TIMER;
    if (enable_high_perf) {
        if (apply_linux_timerslack_1ns()) {
            applied |= FUJI_OS_CAP_TIMER_PERIOD_1MS;
        }
    }
#ifdef MADV_HUGEPAGE
    applied |= FUJI_OS_CAP_MADV_HUGEPAGE;
#endif
    return applied;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_tune_current_thread(int32_t role) {
    (void)role;
    int32_t applied = 0;
    if (apply_linux_timerslack_1ns()) {
        applied |= FUJI_OS_CAP_TIMER_PERIOD_1MS | FUJI_OS_CAP_THREAD_PRIORITY;
    }
    return applied;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_acquire_timer_period(int32_t period_ms) {
    if (period_ms < 1 || period_ms > 15) return -1;
    apply_linux_timerslack_1ns();
    return 0;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_release_timer_period(int32_t period_ms) {
    if (period_ms < 1 || period_ms > 15) return -1;
    return 0;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_sleep_nanos(int64_t duration_ns) {
    if (duration_ns <= 0) return 0;
    apply_linux_timerslack_1ns();

    struct timespec target;
    if (clock_gettime(CLOCK_MONOTONIC, &target) != 0) return -1;
    int64_t sec_add = duration_ns / 1000000000LL;
    int64_t nsec_add = duration_ns % 1000000000LL;
    target.tv_sec += (time_t)sec_add;
    target.tv_nsec += (long)nsec_add;
    if (target.tv_nsec >= 1000000000L) {
        target.tv_sec += 1;
        target.tv_nsec -= 1000000000L;
    }

    int rc;
    do {
        rc = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &target, NULL);
    } while (rc == EINTR);
    return rc == 0 ? 0 : -1;
}

FUJI_AUDIO_EXPORT void* fuji_os_waiter_create(void) {
    int efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (efd < 0) return NULL;
    FujiLinuxOsWaiter* w = (FujiLinuxOsWaiter*)calloc(1, sizeof(FujiLinuxOsWaiter));
    if (!w) {
        close(efd);
        return NULL;
    }
    w->magic = FUJI_OS_WAITER_MAGIC;
    w->efd = efd;
    return w;
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_reset(void* handle) {
    FujiLinuxOsWaiter* w = (FujiLinuxOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC || w->efd < 0) return;
    uint64_t val = 0;
    while (read(w->efd, &val, sizeof(val)) == (ssize_t)sizeof(val)) {
        /* drain pending wake counter */
    }
}

FUJI_AUDIO_EXPORT int32_t fuji_os_waiter_wait_nanos(void* handle, int64_t duration_ns) {
    FujiLinuxOsWaiter* w = (FujiLinuxOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC || w->efd < 0) return -1;
    if (duration_ns <= 0) return 0;
    apply_linux_timerslack_1ns();

    int64_t deadline_ns = linux_monotonic_ns_now() + duration_ns;
    struct pollfd pfd;
    pfd.fd = w->efd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    for (;;) {
        int64_t rem_ns = deadline_ns - linux_monotonic_ns_now();
        if (rem_ns <= 0) return 0;
        struct timespec ts;
        ts.tv_sec = (time_t)(rem_ns / 1000000000LL);
        ts.tv_nsec = (long)(rem_ns % 1000000000LL);
        pfd.revents = 0;
        int rc = ppoll(&pfd, 1, &ts, NULL);
        if (rc > 0) {
            if (pfd.revents & POLLIN) {
                uint64_t val = 0;
                while (read(w->efd, &val, sizeof(val)) == (ssize_t)sizeof(val)) {
                    /* consume wake signal */
                }
                return 1;
            }
            return 0;
        }
        if (rc == 0) return 0;
        if (errno != EINTR) return -1;
    }
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_wake(void* handle) {
    FujiLinuxOsWaiter* w = (FujiLinuxOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC || w->efd < 0) return;
    uint64_t one = 1;
    ssize_t ignored = write(w->efd, &one, sizeof(one));
    (void)ignored;
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_destroy(void* handle) {
    FujiLinuxOsWaiter* w = (FujiLinuxOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC) return;
    if (w->efd >= 0) {
        close(w->efd);
        w->efd = -1;
    }
    w->magic = 0;
    free(w);
}

FUJI_AUDIO_EXPORT int32_t fuji_os_advise_hugepage(void* addr, uint64_t length) {
    if (!addr || length == 0) return -1;
#ifdef MADV_HUGEPAGE
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t pmask = (uintptr_t)page_size - 1u;
    uintptr_t start = (uintptr_t)addr;
    uintptr_t aligned_start = start & ~pmask;
    uintptr_t end = start + (uintptr_t)length;
    if (end < start) return -1; /* overflow guard */
    uintptr_t aligned_end = (end + pmask) & ~pmask;
    if (aligned_end < end) return -1;
    size_t aligned_len = (size_t)(aligned_end - aligned_start);
    if (aligned_len == 0) return -1;
    if (madvise((void*)aligned_start, aligned_len, MADV_HUGEPAGE) == 0) {
        return 0;
    }
    return -errno;
#else
    return 0;
#endif
}
