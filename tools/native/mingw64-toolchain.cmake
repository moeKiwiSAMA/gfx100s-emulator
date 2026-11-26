# Cross-compilation CMake toolchain for building Windows x86_64 native bridges on Linux
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER x86_64-w64-mingw32-gcc-win32)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++-win32)
set(CMAKE_RC_COMPILER x86_64-w64-mingw32-windres)

# Release codegen tuning for Windows x86_64 (x86-64-v2 baseline: SSE4.2/POPCNT/CMPXCHG16B, required by Win10/11 & JDK 25).
set(CMAKE_C_FLAGS_RELEASE_INIT "-O3 -DNDEBUG -fomit-frame-pointer -march=x86-64-v2 -mtune=generic -funroll-loops -fno-semantic-interposition")
set(CMAKE_CXX_FLAGS_RELEASE_INIT "-O3 -DNDEBUG -fomit-frame-pointer -march=x86-64-v2 -mtune=generic -funroll-loops -fno-semantic-interposition")

# Bundle MinGW runtime & winpthread statically so unicorn.dll has zero external MinGW DLL dependencies on Windows.
# Use -win32 compiler (native Win32 threads) + -static/-Wl,-Bstatic without -Wl,-Bdynamic so -lpthread resolves statically.
# Pass --export-all-symbols --exclude-libs,ALL: uc.c.obj exports all public uc_* APIs, static .a archives avoid bloat/65535 limit,
# and FUJI_EXPORT (__declspec(dllexport)) functions in libarm-softmmu.a still export explicitly.
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++ -Wl,-Bstatic -Wl,--export-all-symbols -Wl,--exclude-libs,ALL")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++ -Wl,-Bstatic")
set(CMAKE_C_STANDARD_LIBRARIES_INIT "-Wl,-Bstatic -lwinpthread")
set(CMAKE_CXX_STANDARD_LIBRARIES_INIT "-Wl,-Bstatic -lwinpthread")

set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
