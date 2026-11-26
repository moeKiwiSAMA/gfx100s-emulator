# Fuji Native Codec Bridge (`libfuji_codec.so` / `fuji_codec.dll`)

Permissive (MIT) native C11 codec accelerator for the Fujifilm GFX100S emulator:

1. **Multi-Threaded Fujifilm LSRAW Bayer Strip Codec** (`fuji_lsraw_encode_bayer` / `fuji_lsraw_decode_bayer`):
   - Parallelizes Fujifilm's independent horizontal Bayer 2x2 gradient-adaptive Golomb-Rice strips (`blocksX = 8..16`) across CPU cores using POSIX `pthreads` on Linux and Win32 `_beginthreadex` on Windows x86_64.
   - Produces **bit-for-bit identical (`0`-byte diff)** output to `LsrawCodecEngine.kt` across 12/14/16-bit Lossless (`version=1`) and Compressed (`version=2`) modes.
2. **Direct Native JPEG YUV422 / BGR24 Codec** (`fuji_jpeg_probe_header`, `fuji_jpeg_decode_to_uyvy`, `fuji_jpeg_encode_uyvy_alloc`, `fuji_jpeg_encode_bgr24_alloc`):
   - Dynamically loads system `libjpeg-turbo` (`libjpeg.so.8` v80 or `libturbojpeg.so.0` on Linux; `turbojpeg.dll` or `libjpeg-8.dll` on Windows) at runtime via `dlopen`/`LoadLibraryA` — zero static dependency and zero extra DLLs.
   - Eliminates RGB round-trips by encoding UYVY422 directly via streaming 8-line planar YUV422 `jpeg_write_raw_data` and decoding directly via `JCS_YCbCr` + hardware `scale_denom` (1/2/4/8) IDCT downscaling.
   - Sanitizes Milbeaut unwritten/black fill words (`0x00000000`, `[00 00 80 80]`, `[80 80 00 00]`) to neutral black (`Y=0, Cb=128, Cr=128`).

## License Compliance

- `fuji_codec.c` / `fuji_codec.h`: MIT.
- Optional runtime `libjpeg-turbo`: BSD-3-Clause / IJG / zlib. Zero GPL/LGPL dependencies.
- Windows `fuji_codec.dll` imports ONLY `KERNEL32.dll` and `msvcrt.dll` (verified by `build_codec.sh`).

## Build

```bash
bash tools/native/codec-bridge/build_codec.sh all
```

Outputs:
- `.tools/codec-bridge/build-linux/libfuji_codec.so`
- `.tools/codec-bridge/build-win64/fuji_codec.dll`
