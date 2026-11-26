# `fuji_audio` — Low-Latency Full-Duplex Native Audio & Loopback Bridge (M10)

Lightweight zero-external-runtime C11 audio bridge (`ABI v1`) accessed from Java 25 via Foreign Function & Memory (FFM) (`NativeAudioBridge.java`):

- **Windows x86_64 (`fuji_audio_win32.c` -> `fuji_audio.dll`)**:
  - Pure Win32 **WASAPI** (`IMMDeviceEnumerator`, `IAudioClient`, `IAudioRenderClient`, `IAudioCaptureClient`, `AvSetMmThreadCharacteristicsW("Pro Audio")`).
  - Shared-mode low-latency (~10 ms) output with `AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY`.
  - Microphone capture (`eCapture`) AND **System Output Loopback capture (`AUDCLNT_STREAMFLAGS_LOOPBACK` on any `eRender` output device)** with silent render keepalive so loopback delivers steady frames even during silence.
  - Zero MinGW runtime DLL dependencies (imports ONLY Windows system DLLs: `KERNEL32.dll`, `ole32.dll`, `AVRT.dll`, `msvcrt.dll`).
- **Linux x86_64 (`fuji_audio_linux.c` -> `libfuji_audio.so`)**:
  - Runtime `dlopen("libpulse.so.0")` / `dlopen("libpulse-simple.so.0")` supporting **PipeWire (`pipewire-pulse`)** and **PulseAudio** with zero compile/link-time `libpulse` dependency (gracefully returns 0 devices / `NULL` on headless systems without an audio daemon).
  - Low-latency playback (`pa_simple` with tuned `tlength`/`minreq`), microphone capture, and **system output loopback capture (`<sink>.monitor` / `@DEFAULT_MONITOR@`)** via non-blocking `pa_threaded_mainloop` + `pa_stream` with timed reads.

## Building (Artifacts Stay in Gitignored `.tools/audio-bridge/`)

```sh
# Build Linux libfuji_audio.so + run C self-test
./tools/native/audio-bridge/build_audio.sh linux

# Cross-compile Windows fuji_audio.dll via MinGW-w64
./tools/native/audio-bridge/build_audio.sh win64

# Build both
./tools/native/audio-bridge/build_audio.sh all
```
