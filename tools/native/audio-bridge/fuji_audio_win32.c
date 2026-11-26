#define COBJMACROS
#define CINTERFACE
#define WIN32_LEAN_AND_MEAN
#define INITGUID

#include "fuji_audio.h"

#include <windows.h>
#include <objbase.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <avrt.h>
#include <propsys.h>
#include <propvarutil.h>
#include <mmreg.h>
#include <ksmedia.h>

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifndef AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
#define AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM 0x80000000u
#endif
#ifndef AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY
#define AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY 0x08000000u
#endif

/* Explicit GUID definitions so zero extra MinGW UUID import libs are needed. */
static const CLSID FUJI_CLSID_MMDeviceEnumerator = {
    0xBCDE0395u, 0xE52Fu, 0x467Cu, {0x8Eu, 0x3Du, 0xC4u, 0x57u, 0x92u, 0x91u, 0x69u, 0x2Eu}
};
static const IID FUJI_IID_IMMDeviceEnumerator = {
    0xA95664D2u, 0x9614u, 0x4F35u, {0xA7u, 0x46u, 0xDEu, 0x8Du, 0xB6u, 0x36u, 0x17u, 0xE6u}
};
static const IID FUJI_IID_IAudioClient = {
    0x1CB9AD4Cu, 0xDBFAu, 0x4C32u, {0xB1u, 0x78u, 0xC2u, 0xF5u, 0x68u, 0xA7u, 0x03u, 0xB2u}
};
static const IID FUJI_IID_IAudioRenderClient = {
    0xF294ACFCu, 0x3146u, 0x4483u, {0xA7u, 0xBFu, 0xADu, 0xDCu, 0xA7u, 0xC2u, 0x60u, 0xE2u}
};
static const IID FUJI_IID_IAudioCaptureClient = {
    0xC8ADBD64u, 0xE71Eu, 0x48A0u, {0xA4u, 0xDEu, 0x18u, 0x5Cu, 0x39u, 0x5Cu, 0xD3u, 0x17u}
};
static const PROPERTYKEY FUJI_PKEY_Device_FriendlyName = {
    {0xA45C254Eu, 0xDF1Cu, 0x4EFDu, {0x80u, 0x20u, 0x67u, 0xD1u, 0x46u, 0xA8u, 0x50u, 0xE0u}},
    14u
};

#define FUJI_AUDIO_MAGIC 0x46554A41u

typedef struct FujiWinAudioStream {
    uint32_t magic;
    int32_t is_input;
    int32_t is_loopback;
    int32_t sample_rate_hz;
    int32_t channels;
    int32_t latency_ms;
    uint32_t buffer_frames;
    int32_t com_initialized;

    IMMDevice* device;
    IAudioClient* client;
    IAudioRenderClient* render_client;
    IAudioCaptureClient* capture_client;
    HANDLE wake_event;
    HANDLE avrt_handle;

    /* Silent keepalive render client for loopback capture during system silence */
    IAudioClient* keepalive_client;
    IAudioRenderClient* keepalive_render;

    /* Staging ring for partial WASAPI capture packets */
    int16_t* cap_buf;
    int32_t cap_cap_frames;
    int32_t cap_head_frames;
    int32_t cap_avail_frames;
} FujiWinAudioStream;

static int com_ensure_init(void) {
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (hr == S_OK || hr == S_FALSE) {
        return 1; /* caller should call CoUninitialize */
    }
    return 0; /* RPC_E_CHANGED_MODE or already STA: safe to proceed without uninit */
}

static void wide_to_utf8(const WCHAR* src, char* dst, size_t dst_cap) {
    if (!dst || dst_cap == 0) return;
    dst[0] = '\0';
    if (!src) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, src, -1, dst, (int)dst_cap, NULL, NULL);
    if (n <= 0) {
        dst[0] = '\0';
    } else {
        dst[dst_cap - 1] = '\0';
    }
}

static WCHAR* utf8_to_wide_alloc(const char* src) {
    if (!src || src[0] == '\0') return NULL;
    int len = MultiByteToWideChar(CP_UTF8, 0, src, -1, NULL, 0);
    if (len <= 0) return NULL;
    WCHAR* buf = (WCHAR*)calloc((size_t)len, sizeof(WCHAR));
    if (!buf) return NULL;
    if (MultiByteToWideChar(CP_UTF8, 0, src, -1, buf, len) <= 0) {
        free(buf);
        return NULL;
    }
    return buf;
}

static void fill_pcm16_format(WAVEFORMATEXTENSIBLE* wf, int32_t sample_rate_hz, int32_t channels) {
    memset(wf, 0, sizeof(*wf));
    wf->Format.wFormatTag = WAVE_FORMAT_PCM;
    wf->Format.nChannels = (WORD)channels;
    wf->Format.nSamplesPerSec = (DWORD)sample_rate_hz;
    wf->Format.wBitsPerSample = 16;
    wf->Format.nBlockAlign = (WORD)(channels * 2);
    wf->Format.nAvgBytesPerSec = (DWORD)(sample_rate_hz * channels * 2);
    wf->Format.cbSize = 0;
}

static void query_device_mix_format(IMMDevice* dev, int32_t* out_rate, int32_t* out_ch) {
    *out_rate = 48000;
    *out_ch = 2;
    IAudioClient* client = NULL;
    if (SUCCEEDED(IMMDevice_Activate(dev, &FUJI_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&client)) && client) {
        WAVEFORMATEX* mix = NULL;
        if (SUCCEEDED(IAudioClient_GetMixFormat(client, &mix)) && mix) {
            if (mix->nSamplesPerSec >= 8000 && mix->nSamplesPerSec <= 384000) {
                *out_rate = (int32_t)mix->nSamplesPerSec;
            }
            if (mix->nChannels >= 1 && mix->nChannels <= 8) {
                *out_ch = (int32_t)mix->nChannels;
            }
            CoTaskMemFree(mix);
        }
        IAudioClient_Release(client);
    }
}

static IMMDevice* open_mmdevice(EDataFlow flow, const char* device_id) {
    IMMDeviceEnumerator* enumerator = NULL;
    HRESULT hr = CoCreateInstance(&FUJI_CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL,
                                  &FUJI_IID_IMMDeviceEnumerator, (void**)&enumerator);
    if (FAILED(hr) || !enumerator) return NULL;

    IMMDevice* dev = NULL;
    if (device_id && device_id[0] != '\0') {
        WCHAR* wid = utf8_to_wide_alloc(device_id);
        if (wid) {
            hr = IMMDeviceEnumerator_GetDevice(enumerator, wid, &dev);
            free(wid);
            if (FAILED(hr)) dev = NULL;
        }
    }
    if (!dev) {
        hr = IMMDeviceEnumerator_GetDefaultAudioEndpoint(enumerator, flow, eConsole, &dev);
        if (FAILED(hr)) dev = NULL;
    }
    IMMDeviceEnumerator_Release(enumerator);
    return dev;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_abi_version(void) {
    return FUJI_AUDIO_ABI_VERSION;
}

FUJI_AUDIO_EXPORT const char* fuji_audio_backend_name(void) {
    return "wasapi";
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_enumerate(int32_t direction, FujiAudioDeviceInfo* out, int32_t max_count) {
    if (direction < FUJI_AUDIO_DIR_OUTPUT || direction > FUJI_AUDIO_DIR_LOOPBACK) return -1;
    if (max_count <= 0 || !out) return 0;

    int uninit = com_ensure_init();
    EDataFlow flow = (direction == FUJI_AUDIO_DIR_INPUT) ? eCapture : eRender;

    IMMDeviceEnumerator* enumerator = NULL;
    HRESULT hr = CoCreateInstance(&FUJI_CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL,
                                  &FUJI_IID_IMMDeviceEnumerator, (void**)&enumerator);
    if (FAILED(hr) || !enumerator) {
        if (uninit) CoUninitialize();
        return 0;
    }

    WCHAR* default_id = NULL;
    IMMDevice* def_dev = NULL;
    if (SUCCEEDED(IMMDeviceEnumerator_GetDefaultAudioEndpoint(enumerator, flow, eConsole, &def_dev)) && def_dev) {
        IMMDevice_GetId(def_dev, &default_id);
        IMMDevice_Release(def_dev);
    }

    IMMDeviceCollection* coll = NULL;
    int32_t written = 0;
    if (SUCCEEDED(IMMDeviceEnumerator_EnumAudioEndpoints(enumerator, flow, DEVICE_STATE_ACTIVE, &coll)) && coll) {
        UINT count = 0;
        if (SUCCEEDED(IMMDeviceCollection_GetCount(coll, &count))) {
            for (UINT i = 0; i < count && written < max_count; ++i) {
                IMMDevice* dev = NULL;
                if (FAILED(IMMDeviceCollection_Item(coll, i, &dev)) || !dev) continue;

                FujiAudioDeviceInfo info;
                memset(&info, 0, sizeof(info));

                WCHAR* dev_id = NULL;
                if (SUCCEEDED(IMMDevice_GetId(dev, &dev_id)) && dev_id) {
                    wide_to_utf8(dev_id, info.id, sizeof(info.id));
                    if (default_id && wcscmp(dev_id, default_id) == 0) {
                        info.is_default = 1;
                    }
                    CoTaskMemFree(dev_id);
                }

                IPropertyStore* store = NULL;
                if (SUCCEEDED(IMMDevice_OpenPropertyStore(dev, STGM_READ, &store)) && store) {
                    PROPVARIANT pv;
                    PropVariantInit(&pv);
                    if (SUCCEEDED(IPropertyStore_GetValue(store, &FUJI_PKEY_Device_FriendlyName, &pv))) {
                        if (pv.vt == VT_LPWSTR && pv.pwszVal) {
                            wide_to_utf8(pv.pwszVal, info.name, sizeof(info.name));
                        }
                        PropVariantClear(&pv);
                    }
                    IPropertyStore_Release(store);
                }
                if (info.name[0] == '\0') {
                    memcpy(info.name, info.id, sizeof(info.name));
                    info.name[sizeof(info.name) - 1] = '\0';
                }

                query_device_mix_format(dev, &info.sample_rate_hz, &info.channels);
                out[written++] = info;
                IMMDevice_Release(dev);
            }
        }
        IMMDeviceCollection_Release(coll);
    }

    if (default_id) CoTaskMemFree(default_id);
    IMMDeviceEnumerator_Release(enumerator);
    if (uninit) CoUninitialize();
    return written;
}

static void free_stream_resources(FujiWinAudioStream* s) {
    if (!s) return;
    if (s->client) {
        IAudioClient_Stop(s->client);
    }
    if (s->keepalive_client) {
        IAudioClient_Stop(s->keepalive_client);
    }
    if (s->keepalive_render) IAudioRenderClient_Release(s->keepalive_render);
    if (s->keepalive_client) IAudioClient_Release(s->keepalive_client);
    if (s->render_client) IAudioRenderClient_Release(s->render_client);
    if (s->capture_client) IAudioCaptureClient_Release(s->capture_client);
    if (s->client) IAudioClient_Release(s->client);
    if (s->device) IMMDevice_Release(s->device);
    if (s->wake_event) CloseHandle(s->wake_event);
    if (s->avrt_handle) AvRevertMmThreadCharacteristics(s->avrt_handle);
    free(s->cap_buf);
    if (s->com_initialized) CoUninitialize();
    s->magic = 0;
    free(s);
}

FUJI_AUDIO_EXPORT void* fuji_audio_open_output(const char* device_id, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms) {
    if (sample_rate_hz < 8000 || sample_rate_hz > 192000) return NULL;
    if (channels < 1 || channels > 2) return NULL;
    if (target_latency_ms < 5) target_latency_ms = 5;
    if (target_latency_ms > 500) target_latency_ms = 500;

    FujiWinAudioStream* s = (FujiWinAudioStream*)calloc(1, sizeof(FujiWinAudioStream));
    if (!s) return NULL;
    s->magic = FUJI_AUDIO_MAGIC;
    s->is_input = 0;
    s->sample_rate_hz = sample_rate_hz;
    s->channels = channels;
    s->latency_ms = target_latency_ms;
    s->com_initialized = com_ensure_init();

    s->device = open_mmdevice(eRender, device_id);
    if (!s->device) { free_stream_resources(s); return NULL; }

    HRESULT hr = IMMDevice_Activate(s->device, &FUJI_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&s->client);
    if (FAILED(hr) || !s->client) { free_stream_resources(s); return NULL; }

    REFERENCE_TIME def_period = 0, min_period = 0;
    IAudioClient_GetDevicePeriod(s->client, &def_period, &min_period);
    REFERENCE_TIME buf_dur = (REFERENCE_TIME)target_latency_ms * 10000;
    if (buf_dur < def_period) buf_dur = def_period;

    WAVEFORMATEXTENSIBLE wf;
    fill_pcm16_format(&wf, sample_rate_hz, channels);
    DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
                  AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
                  AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;

    hr = IAudioClient_Initialize(s->client, AUDCLNT_SHAREMODE_SHARED, flags, buf_dur, 0, &wf.Format, NULL);
    if (FAILED(hr)) {
        /* Fallback without event callback on legacy drivers */
        flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        hr = IAudioClient_Initialize(s->client, AUDCLNT_SHAREMODE_SHARED, flags, buf_dur, 0, &wf.Format, NULL);
        if (FAILED(hr)) { free_stream_resources(s); return NULL; }
    } else {
        s->wake_event = CreateEventW(NULL, FALSE, FALSE, NULL);
        if (!s->wake_event || FAILED(IAudioClient_SetEventHandle(s->client, s->wake_event))) {
            free_stream_resources(s);
            return NULL;
        }
    }

    if (FAILED(IAudioClient_GetBufferSize(s->client, &s->buffer_frames)) || s->buffer_frames == 0) {
        free_stream_resources(s);
        return NULL;
    }

    if (FAILED(IAudioClient_GetService(s->client, &FUJI_IID_IAudioRenderClient, (void**)&s->render_client)) || !s->render_client) {
        free_stream_resources(s);
        return NULL;
    }

    /* Pre-fill buffer with silence before Start() to avoid initial pop/underrun */
    BYTE* data = NULL;
    if (SUCCEEDED(IAudioRenderClient_GetBuffer(s->render_client, s->buffer_frames, &data))) {
        IAudioRenderClient_ReleaseBuffer(s->render_client, s->buffer_frames, AUDCLNT_BUFFERFLAGS_SILENT);
    }

    REFERENCE_TIME actual_lat = 0;
    if (SUCCEEDED(IAudioClient_GetStreamLatency(s->client, &actual_lat)) && actual_lat > 0) {
        s->latency_ms = (int32_t)((actual_lat + 5000) / 10000);
        if (s->latency_ms < 1) s->latency_ms = 1;
    } else {
        s->latency_ms = (int32_t)((s->buffer_frames * 1000u) / (uint32_t)sample_rate_hz);
    }

    DWORD task_idx = 0;
    s->avrt_handle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task_idx);

    if (FAILED(IAudioClient_Start(s->client))) {
        free_stream_resources(s);
        return NULL;
    }
    return s;
}

static void start_loopback_silent_keepalive(FujiWinAudioStream* s) {
    if (!s || !s->device) return;
    IAudioClient* kc = NULL;
    if (FAILED(IMMDevice_Activate(s->device, &FUJI_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&kc)) || !kc) {
        return;
    }
    WAVEFORMATEX* mix = NULL;
    if (FAILED(IAudioClient_GetMixFormat(kc, &mix)) || !mix) {
        IAudioClient_Release(kc);
        return;
    }
    REFERENCE_TIME dur = 200000; /* 20 ms silent keepalive */
    HRESULT hr = IAudioClient_Initialize(kc, AUDCLNT_SHAREMODE_SHARED, 0, dur, 0, mix, NULL);
    CoTaskMemFree(mix);
    if (FAILED(hr)) {
        IAudioClient_Release(kc);
        return;
    }
    IAudioRenderClient* kr = NULL;
    UINT32 kframes = 0;
    if (SUCCEEDED(IAudioClient_GetBufferSize(kc, &kframes)) && kframes > 0 &&
        SUCCEEDED(IAudioClient_GetService(kc, &FUJI_IID_IAudioRenderClient, (void**)&kr)) && kr) {
        BYTE* p = NULL;
        if (SUCCEEDED(IAudioRenderClient_GetBuffer(kr, kframes, &p))) {
            IAudioRenderClient_ReleaseBuffer(kr, kframes, AUDCLNT_BUFFERFLAGS_SILENT);
        }
        if (SUCCEEDED(IAudioClient_Start(kc))) {
            s->keepalive_client = kc;
            s->keepalive_render = kr;
            return;
        }
        IAudioRenderClient_Release(kr);
    }
    IAudioClient_Release(kc);
}

FUJI_AUDIO_EXPORT void* fuji_audio_open_input(const char* device_id, int32_t loopback, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms) {
    if (sample_rate_hz < 8000 || sample_rate_hz > 192000) return NULL;
    if (channels < 1 || channels > 2) return NULL;
    if (target_latency_ms < 5) target_latency_ms = 5;
    if (target_latency_ms > 500) target_latency_ms = 500;

    FujiWinAudioStream* s = (FujiWinAudioStream*)calloc(1, sizeof(FujiWinAudioStream));
    if (!s) return NULL;
    s->magic = FUJI_AUDIO_MAGIC;
    s->is_input = 1;
    s->is_loopback = (loopback != 0) ? 1 : 0;
    s->sample_rate_hz = sample_rate_hz;
    s->channels = channels;
    s->latency_ms = target_latency_ms;
    s->com_initialized = com_ensure_init();

    EDataFlow flow = s->is_loopback ? eRender : eCapture;
    s->device = open_mmdevice(flow, device_id);
    if (!s->device) { free_stream_resources(s); return NULL; }

    HRESULT hr = IMMDevice_Activate(s->device, &FUJI_IID_IAudioClient, CLSCTX_ALL, NULL, (void**)&s->client);
    if (FAILED(hr) || !s->client) { free_stream_resources(s); return NULL; }

    REFERENCE_TIME def_period = 0, min_period = 0;
    IAudioClient_GetDevicePeriod(s->client, &def_period, &min_period);
    REFERENCE_TIME buf_dur = (REFERENCE_TIME)target_latency_ms * 10000;
    if (buf_dur < def_period * 2) buf_dur = def_period * 2;

    WAVEFORMATEXTENSIBLE wf;
    fill_pcm16_format(&wf, sample_rate_hz, channels);

    DWORD flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    if (s->is_loopback) {
        flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;
    } else {
        flags |= AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    }

    hr = IAudioClient_Initialize(s->client, AUDCLNT_SHAREMODE_SHARED, flags, buf_dur, 0, &wf.Format, NULL);
    if (FAILED(hr) && !s->is_loopback) {
        flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        hr = IAudioClient_Initialize(s->client, AUDCLNT_SHAREMODE_SHARED, flags, buf_dur, 0, &wf.Format, NULL);
    }
    if (FAILED(hr)) { free_stream_resources(s); return NULL; }

    if (flags & AUDCLNT_STREAMFLAGS_EVENTCALLBACK) {
        s->wake_event = CreateEventW(NULL, FALSE, FALSE, NULL);
        if (!s->wake_event || FAILED(IAudioClient_SetEventHandle(s->client, s->wake_event))) {
            free_stream_resources(s);
            return NULL;
        }
    }

    if (FAILED(IAudioClient_GetBufferSize(s->client, &s->buffer_frames)) || s->buffer_frames == 0) {
        free_stream_resources(s);
        return NULL;
    }

    if (FAILED(IAudioClient_GetService(s->client, &FUJI_IID_IAudioCaptureClient, (void**)&s->capture_client)) || !s->capture_client) {
        free_stream_resources(s);
        return NULL;
    }

    s->cap_cap_frames = (int32_t)(s->buffer_frames * 4u);
    if (s->cap_cap_frames < 8192) s->cap_cap_frames = 8192;
    s->cap_buf = (int16_t*)calloc((size_t)s->cap_cap_frames * (size_t)channels, sizeof(int16_t));
    if (!s->cap_buf) { free_stream_resources(s); return NULL; }

    if (s->is_loopback) {
        start_loopback_silent_keepalive(s);
    }

    REFERENCE_TIME actual_lat = 0;
    if (SUCCEEDED(IAudioClient_GetStreamLatency(s->client, &actual_lat)) && actual_lat > 0) {
        s->latency_ms = (int32_t)((actual_lat + 5000) / 10000);
        if (s->latency_ms < 1) s->latency_ms = 1;
    } else {
        s->latency_ms = (int32_t)((s->buffer_frames * 1000u) / (uint32_t)sample_rate_hz);
    }

    DWORD task_idx = 0;
    s->avrt_handle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task_idx);

    if (FAILED(IAudioClient_Start(s->client))) {
        free_stream_resources(s);
        return NULL;
    }
    return s;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_write(void* handle, const int16_t* interleaved_pcm, int32_t frames) {
    FujiWinAudioStream* s = (FujiWinAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC || s->is_input || !s->render_client) return -1;
    if (frames <= 0) return 0;
    if (!interleaved_pcm) return -1;

    int32_t written = 0;
    int wait_attempts = 0;
    while (written < frames) {
        UINT32 padding = 0;
        HRESULT hr = IAudioClient_GetCurrentPadding(s->client, &padding);
        if (FAILED(hr)) return -2;

        UINT32 avail = (s->buffer_frames > padding) ? (s->buffer_frames - padding) : 0u;
        if (avail == 0u) {
            if (++wait_attempts > 25) break;
            if (s->wake_event) {
                WaitForSingleObject(s->wake_event, 20);
            } else {
                Sleep(2);
            }
            continue;
        }
        wait_attempts = 0;
        UINT32 to_write = (UINT32)(frames - written);
        if (to_write > avail) to_write = avail;

        BYTE* dst = NULL;
        hr = IAudioRenderClient_GetBuffer(s->render_client, to_write, &dst);
        if (FAILED(hr) || !dst) return -3;
        memcpy(dst, interleaved_pcm + (size_t)written * (size_t)s->channels,
               (size_t)to_write * (size_t)s->channels * sizeof(int16_t));
        hr = IAudioRenderClient_ReleaseBuffer(s->render_client, to_write, 0);
        if (FAILED(hr)) return -4;
        written += (int32_t)to_write;
    }
    return written;
}

static void drain_wasapi_packets(FujiWinAudioStream* s) {
    UINT32 packet_len = 0;
    while (SUCCEEDED(IAudioCaptureClient_GetNextPacketSize(s->capture_client, &packet_len)) && packet_len > 0) {
        BYTE* data = NULL;
        UINT32 num_frames = 0;
        DWORD flags = 0;
        HRESULT hr = IAudioCaptureClient_GetBuffer(s->capture_client, &data, &num_frames, &flags, NULL, NULL);
        if (FAILED(hr)) break;

        if (num_frames > 0) {
            int32_t n = (int32_t)num_frames;
            int32_t ch = s->channels;
            int32_t silent = ((flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || !data);
            for (int32_t i = 0; i < n; ++i) {
                int32_t write_pos;
                if (s->cap_avail_frames < s->cap_cap_frames) {
                    write_pos = (s->cap_head_frames + s->cap_avail_frames) % s->cap_cap_frames;
                    s->cap_avail_frames++;
                } else {
                    /* Overwrite oldest frame on overrun so capture never stalls */
                    write_pos = s->cap_head_frames;
                    s->cap_head_frames = (s->cap_head_frames + 1) % s->cap_cap_frames;
                }
                int16_t* dst = s->cap_buf + (size_t)write_pos * (size_t)ch;
                if (silent) {
                    for (int32_t c = 0; c < ch; ++c) dst[c] = 0;
                } else {
                    const int16_t* src = ((const int16_t*)data) + (size_t)i * (size_t)ch;
                    for (int32_t c = 0; c < ch; ++c) dst[c] = src[c];
                }
            }
        }
        IAudioCaptureClient_ReleaseBuffer(s->capture_client, num_frames);
    }
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_read(void* handle, int16_t* interleaved_pcm, int32_t max_frames, int32_t timeout_ms) {
    FujiWinAudioStream* s = (FujiWinAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC || !s->is_input || !s->capture_client) return -1;
    if (max_frames <= 0) return 0;
    if (!interleaved_pcm) return -1;
    if (timeout_ms < 0) timeout_ms = 0;

    drain_wasapi_packets(s);

    ULONGLONG start_ms = GetTickCount64();
    while (s->cap_avail_frames == 0 && timeout_ms > 0) {
        ULONGLONG elapsed = GetTickCount64() - start_ms;
        if (elapsed >= (ULONGLONG)timeout_ms) break;
        DWORD rem = (DWORD)((ULONGLONG)timeout_ms - elapsed);
        if (s->wake_event) {
            WaitForSingleObject(s->wake_event, rem > 20 ? 20 : rem);
        } else {
            Sleep(rem > 2 ? 2 : rem);
        }
        drain_wasapi_packets(s);
    }

    int32_t to_read = (s->cap_avail_frames < max_frames) ? s->cap_avail_frames : max_frames;
    int32_t ch = s->channels;
    for (int32_t i = 0; i < to_read; ++i) {
        const int16_t* src = s->cap_buf + (size_t)s->cap_head_frames * (size_t)ch;
        int16_t* dst = interleaved_pcm + (size_t)i * (size_t)ch;
        for (int32_t c = 0; c < ch; ++c) dst[c] = src[c];
        s->cap_head_frames = (s->cap_head_frames + 1) % s->cap_cap_frames;
        s->cap_avail_frames--;
    }
    return to_read;
}

FUJI_AUDIO_EXPORT int32_t fuji_audio_get_latency_ms(void* handle) {
    FujiWinAudioStream* s = (FujiWinAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC) return -1;
    return s->latency_ms;
}

FUJI_AUDIO_EXPORT void fuji_audio_close(void* handle) {
    FujiWinAudioStream* s = (FujiWinAudioStream*)handle;
    if (!s || s->magic != FUJI_AUDIO_MAGIC) return;
    free_stream_resources(s);
}

/* --------------------------------------------------------------------------
 * Windows OS-Native High-Resolution Timer, Anti-EcoQoS & P-Core Tuning (v1)
 * Zero extra link dependencies: kernel32/winmm symbols resolved dynamically.
 * -------------------------------------------------------------------------- */
#define FUJI_OS_WAITER_MAGIC 0x464F5357u
#define FUJI_CREATE_WAITABLE_TIMER_MANUAL_RESET     0x00000001u
#define FUJI_CREATE_WAITABLE_TIMER_HIGH_RESOLUTION  0x00000002u
#define FUJI_PROCESS_POWER_THROTTLING_INFO          4
#define FUJI_THREAD_POWER_THROTTLING_INFO           3
#define FUJI_POWER_THROTTLING_CURRENT_VERSION       1u
#define FUJI_POWER_THROTTLING_EXECUTION_SPEED       0x1u
#define FUJI_POWER_THROTTLING_IGNORE_TIMER_RES      0x4u

typedef struct FujiPowerThrottlingState {
    ULONG Version;
    ULONG ControlMask;
    ULONG StateMask;
} FujiPowerThrottlingState;

typedef HANDLE (WINAPI *PFN_CreateWaitableTimerExW)(LPSECURITY_ATTRIBUTES, LPCWSTR, DWORD, DWORD);
typedef BOOL   (WINAPI *PFN_SetProcessInformation)(HANDLE, INT, LPVOID, DWORD);
typedef BOOL   (WINAPI *PFN_SetThreadInformation)(HANDLE, INT, LPVOID, DWORD);
typedef UINT   (WINAPI *PFN_timeBeginPeriod)(UINT);
typedef UINT   (WINAPI *PFN_timeEndPeriod)(UINT);

typedef struct FujiWinOsApi {
    volatile LONG init_state; /* 0 = uninit, 1 = initializing, 2 = ready */
    PFN_CreateWaitableTimerExW pCreateWaitableTimerExW;
    PFN_SetProcessInformation  pSetProcessInformation;
    PFN_SetThreadInformation   pSetThreadInformation;
    PFN_timeBeginPeriod        pTimeBeginPeriod;
    PFN_timeEndPeriod          pTimeEndPeriod;
    int32_t                    highres_timer_supported;
    LARGE_INTEGER              qpc_freq;
} FujiWinOsApi;

static FujiWinOsApi g_os = {0};
static DWORD g_sleep_timer_tls = TLS_OUT_OF_INDEXES;
static volatile LONG g_timer_period_refs[16] = {0};
static volatile LONG g_process_period_acquired = 0;

static HANDLE create_best_waitable_timer(int32_t* out_is_highres);

static void copy_proc_addr(HMODULE mod, const char* name, void* dst_fn_ptr) {
    FARPROC fp = mod ? GetProcAddress(mod, name) : NULL;
    memcpy(dst_fn_ptr, &fp, sizeof(fp));
}

static void os_ensure_init(void) {
    if (InterlockedCompareExchange(&g_os.init_state, 2, 2) == 2) return;
    if (InterlockedCompareExchange(&g_os.init_state, 1, 0) == 0) {
        HMODULE k32 = GetModuleHandleA("kernel32.dll");
        if (k32) {
            copy_proc_addr(k32, "CreateWaitableTimerExW", &g_os.pCreateWaitableTimerExW);
            copy_proc_addr(k32, "SetProcessInformation", &g_os.pSetProcessInformation);
            copy_proc_addr(k32, "SetThreadInformation", &g_os.pSetThreadInformation);
        }
        HMODULE winmm = LoadLibraryA("winmm.dll");
        if (winmm) {
            copy_proc_addr(winmm, "timeBeginPeriod", &g_os.pTimeBeginPeriod);
            copy_proc_addr(winmm, "timeEndPeriod", &g_os.pTimeEndPeriod);
        }
        QueryPerformanceFrequency(&g_os.qpc_freq);
        if (g_os.qpc_freq.QuadPart <= 0) {
            g_os.qpc_freq.QuadPart = 10000000LL;
        }
        int32_t hi = 0;
        HANDLE probe = create_best_waitable_timer(&hi);
        if (probe) CloseHandle(probe);
        g_os.highres_timer_supported = hi;
        InterlockedExchange(&g_os.init_state, 2);
    } else {
        while (InterlockedCompareExchange(&g_os.init_state, 2, 2) != 2) {
            Sleep(0);
        }
    }
}

static HANDLE create_best_waitable_timer(int32_t* out_is_highres) {
    if (out_is_highres) *out_is_highres = 0;
    if (g_os.pCreateWaitableTimerExW) {
        HANDLE h = g_os.pCreateWaitableTimerExW(
            NULL,
            NULL,
            FUJI_CREATE_WAITABLE_TIMER_MANUAL_RESET | FUJI_CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
            TIMER_ALL_ACCESS
        );
        if (h) {
            if (out_is_highres) *out_is_highres = 1;
            return h;
        }
    }
    return CreateWaitableTimerW(NULL, TRUE, NULL);
}

static HANDLE get_tls_sleep_timer(void) {
    if (g_sleep_timer_tls == TLS_OUT_OF_INDEXES) return NULL;
    HANDLE h = (HANDLE)TlsGetValue(g_sleep_timer_tls);
    if (!h) {
        h = create_best_waitable_timer(NULL);
        if (h) TlsSetValue(g_sleep_timer_tls, h);
    }
    return h;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)hinst;
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_sleep_timer_tls = TlsAlloc();
    } else if (reason == DLL_THREAD_DETACH) {
        if (g_sleep_timer_tls != TLS_OUT_OF_INDEXES) {
            HANDLE h = (HANDLE)TlsGetValue(g_sleep_timer_tls);
            if (h) {
                CloseHandle(h);
                TlsSetValue(g_sleep_timer_tls, NULL);
            }
        }
    } else if (reason == DLL_PROCESS_DETACH) {
        if (g_sleep_timer_tls != TLS_OUT_OF_INDEXES) {
            HANDLE h = (HANDLE)TlsGetValue(g_sleep_timer_tls);
            if (h) CloseHandle(h);
            TlsFree(g_sleep_timer_tls);
            g_sleep_timer_tls = TLS_OUT_OF_INDEXES;
        }
        if (g_os.pTimeEndPeriod) {
            for (int p = 1; p <= 15; ++p) {
                LONG refs = InterlockedExchange(&g_timer_period_refs[p], 0);
                if (refs > 0) g_os.pTimeEndPeriod((UINT)p);
            }
        }
    }
    return TRUE;
}

typedef struct FujiWinOsWaiter {
    uint32_t magic;
    HANDLE timer;
    HANDLE wake_event;
    int32_t is_highres;
} FujiWinOsWaiter;

FUJI_AUDIO_EXPORT int32_t fuji_os_abi_version(void) {
    return FUJI_OS_ABI_VERSION;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_capabilities(void) {
    os_ensure_init();
    int32_t caps = FUJI_OS_CAP_THREAD_PRIORITY;
    if (g_os.highres_timer_supported) caps |= FUJI_OS_CAP_HIGHRES_TIMER;
    if (g_os.pTimeBeginPeriod && g_os.pTimeEndPeriod) caps |= FUJI_OS_CAP_TIMER_PERIOD_1MS;
    if (g_os.pSetProcessInformation || g_os.pSetThreadInformation) caps |= FUJI_OS_CAP_ANTI_ECOQOS;
    return caps;
}

FUJI_AUDIO_EXPORT int64_t fuji_os_monotonic_nanos(void) {
    os_ensure_init();
    LARGE_INTEGER cnt;
    QueryPerformanceCounter(&cnt);
    int64_t freq = g_os.qpc_freq.QuadPart;
    int64_t sec = cnt.QuadPart / freq;
    int64_t rem = cnt.QuadPart % freq;
    return sec * 1000000000LL + (rem * 1000000000LL) / freq;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_acquire_timer_period(int32_t period_ms) {
    if (period_ms < 1 || period_ms > 15) return -1;
    os_ensure_init();
    if (!g_os.pTimeBeginPeriod) return -1;
    LONG after = InterlockedIncrement(&g_timer_period_refs[period_ms]);
    if (after == 1) {
        if (g_os.pTimeBeginPeriod((UINT)period_ms) != 0) {
            InterlockedDecrement(&g_timer_period_refs[period_ms]);
            return -1;
        }
    }
    return 0;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_release_timer_period(int32_t period_ms) {
    if (period_ms < 1 || period_ms > 15) return -1;
    os_ensure_init();
    if (!g_os.pTimeEndPeriod) return -1;
    for (;;) {
        LONG cur = g_timer_period_refs[period_ms];
        if (cur <= 0) return 0;
        if (InterlockedCompareExchange(&g_timer_period_refs[period_ms], cur - 1, cur) == cur) {
            if (cur == 1) {
                g_os.pTimeEndPeriod((UINT)period_ms);
            }
            return 0;
        }
    }
}

FUJI_AUDIO_EXPORT int32_t fuji_os_tune_process(int32_t enable_high_perf) {
    os_ensure_init();
    int32_t applied = 0;
    if (g_os.highres_timer_supported) applied |= FUJI_OS_CAP_HIGHRES_TIMER;

    if (g_os.pSetProcessInformation) {
        FujiPowerThrottlingState st;
        memset(&st, 0, sizeof(st));
        st.Version = FUJI_POWER_THROTTLING_CURRENT_VERSION;
        if (enable_high_perf) {
            /* Disable EcoQoS AND disable Win11 22H2+ background timer-resolution throttling */
            st.ControlMask = FUJI_POWER_THROTTLING_EXECUTION_SPEED | FUJI_POWER_THROTTLING_IGNORE_TIMER_RES;
            st.StateMask = 0u;
            if (g_os.pSetProcessInformation(GetCurrentProcess(), FUJI_PROCESS_POWER_THROTTLING_INFO, &st, (DWORD)sizeof(st))) {
                applied |= FUJI_OS_CAP_ANTI_ECOQOS;
            } else {
                /* Fallback for Win10 / Win11 21H2 that only recognize EXECUTION_SPEED */
                st.ControlMask = FUJI_POWER_THROTTLING_EXECUTION_SPEED;
                st.StateMask = 0u;
                if (g_os.pSetProcessInformation(GetCurrentProcess(), FUJI_PROCESS_POWER_THROTTLING_INFO, &st, (DWORD)sizeof(st))) {
                    applied |= FUJI_OS_CAP_ANTI_ECOQOS;
                }
            }
        } else {
            st.ControlMask = 0u;
            st.StateMask = 0u;
            g_os.pSetProcessInformation(GetCurrentProcess(), FUJI_PROCESS_POWER_THROTTLING_INFO, &st, (DWORD)sizeof(st));
        }
    }

    if (enable_high_perf) {
        if (InterlockedCompareExchange(&g_process_period_acquired, 1, 0) == 0) {
            if (fuji_os_acquire_timer_period(1) == 0) {
                applied |= FUJI_OS_CAP_TIMER_PERIOD_1MS;
            } else {
                InterlockedExchange(&g_process_period_acquired, 0);
            }
        } else {
            applied |= FUJI_OS_CAP_TIMER_PERIOD_1MS;
        }
    } else if (InterlockedCompareExchange(&g_process_period_acquired, 0, 1) == 1) {
        fuji_os_release_timer_period(1);
    }

    return applied;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_tune_current_thread(int32_t role) {
    os_ensure_init();
    int32_t applied = 0;
    int prio = (role == FUJI_OS_ROLE_AUDIO_IO) ? THREAD_PRIORITY_HIGHEST : THREAD_PRIORITY_ABOVE_NORMAL;
    if (SetThreadPriority(GetCurrentThread(), prio)) {
        applied |= FUJI_OS_CAP_THREAD_PRIORITY;
    }
    if (g_os.pSetThreadInformation) {
        FujiPowerThrottlingState st;
        memset(&st, 0, sizeof(st));
        st.Version = FUJI_POWER_THROTTLING_CURRENT_VERSION;
        st.ControlMask = FUJI_POWER_THROTTLING_EXECUTION_SPEED;
        st.StateMask = 0u; /* explicitly disable per-thread EcoQoS -> schedule on P-cores */
        if (g_os.pSetThreadInformation(GetCurrentThread(), FUJI_THREAD_POWER_THROTTLING_INFO, &st, (DWORD)sizeof(st))) {
            applied |= FUJI_OS_CAP_ANTI_ECOQOS;
        }
    }
    return applied;
}

FUJI_AUDIO_EXPORT int32_t fuji_os_sleep_nanos(int64_t duration_ns) {
    if (duration_ns <= 0) return 0;
    os_ensure_init();
    HANDLE timer = get_tls_sleep_timer();
    int owned = 0;
    if (!timer) {
        timer = create_best_waitable_timer(NULL);
        owned = 1;
    }
    if (!timer) {
        DWORD ms = (DWORD)((duration_ns + 999999LL) / 1000000LL);
        Sleep(ms > 0 ? ms : 1);
        return 0;
    }
    int64_t ticks100 = (duration_ns + 99LL) / 100LL;
    if (ticks100 < 1) ticks100 = 1;
    LARGE_INTEGER due;
    due.QuadPart = -ticks100;
    int32_t rc = 0;
    if (!SetWaitableTimer(timer, &due, 0, NULL, NULL, FALSE)) {
        rc = -1;
    } else if (WaitForSingleObject(timer, INFINITE) != WAIT_OBJECT_0) {
        rc = -1;
    }
    if (owned) CloseHandle(timer);
    return rc;
}

FUJI_AUDIO_EXPORT void* fuji_os_waiter_create(void) {
    os_ensure_init();
    int32_t hi = 0;
    HANDLE timer = create_best_waitable_timer(&hi);
    if (!timer) return NULL;
    HANDLE ev = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!ev) {
        CloseHandle(timer);
        return NULL;
    }
    FujiWinOsWaiter* w = (FujiWinOsWaiter*)calloc(1, sizeof(FujiWinOsWaiter));
    if (!w) {
        CloseHandle(ev);
        CloseHandle(timer);
        return NULL;
    }
    w->magic = FUJI_OS_WAITER_MAGIC;
    w->timer = timer;
    w->wake_event = ev;
    w->is_highres = hi;
    return w;
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_reset(void* handle) {
    FujiWinOsWaiter* w = (FujiWinOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC) return;
    if (w->wake_event) ResetEvent(w->wake_event);
}

FUJI_AUDIO_EXPORT int32_t fuji_os_waiter_wait_nanos(void* handle, int64_t duration_ns) {
    FujiWinOsWaiter* w = (FujiWinOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC || !w->timer || !w->wake_event) return -1;
    if (duration_ns <= 0) return 0;

    if (WaitForSingleObject(w->wake_event, 0) == WAIT_OBJECT_0) {
        ResetEvent(w->wake_event);
        return 1;
    }

    int64_t ticks100 = (duration_ns + 99LL) / 100LL;
    if (ticks100 < 1) ticks100 = 1;
    LARGE_INTEGER due;
    due.QuadPart = -ticks100;
    if (!SetWaitableTimer(w->timer, &due, 0, NULL, NULL, FALSE)) return -1;

    HANDLE objs[2] = { w->wake_event, w->timer };
    DWORD res = WaitForMultipleObjects(2, objs, FALSE, INFINITE);
    if (res == WAIT_OBJECT_0) {
        ResetEvent(w->wake_event);
        CancelWaitableTimer(w->timer);
        return 1;
    }
    if (res == WAIT_OBJECT_0 + 1) {
        return 0;
    }
    CancelWaitableTimer(w->timer);
    return -1;
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_wake(void* handle) {
    FujiWinOsWaiter* w = (FujiWinOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC || !w->wake_event) return;
    SetEvent(w->wake_event);
}

FUJI_AUDIO_EXPORT void fuji_os_waiter_destroy(void* handle) {
    FujiWinOsWaiter* w = (FujiWinOsWaiter*)handle;
    if (!w || w->magic != FUJI_OS_WAITER_MAGIC) return;
    if (w->wake_event) {
        SetEvent(w->wake_event);
        CloseHandle(w->wake_event);
        w->wake_event = NULL;
    }
    if (w->timer) {
        CancelWaitableTimer(w->timer);
        CloseHandle(w->timer);
        w->timer = NULL;
    }
    w->magic = 0;
    free(w);
}

FUJI_AUDIO_EXPORT int32_t fuji_os_advise_hugepage(void* addr, uint64_t length) {
    if (!addr || length == 0) return -1;
    return 0; /* Safe no-op on Windows (Large Pages require SeLockMemoryPrivilege) */
}
