# Cross-compilation CMake toolchain for building Windows x86_64 fuji_dynarmic.dll on Linux using MinGW-w64 (POSIX threads for C++20 std::mutex/std::thread)
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER x86_64-w64-mingw32-gcc-posix)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++-posix)
set(CMAKE_RC_COMPILER x86_64-w64-mingw32-windres)

# Static link libstdc++, libgcc, and winpthread so fuji_dynarmic.dll has zero external MinGW DLL dependencies on Windows.
# Do NOT pass -Wl,-Bdynamic: GCC appends implicit -lstdc++/-lpthread after linker flags, and Windows import libs are .a archives.
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++ -Wl,-Bstatic")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static -static-libgcc -static-libstdc++ -Wl,-Bstatic")
set(CMAKE_C_STANDARD_LIBRARIES_INIT "-Wl,-Bstatic -lwinpthread")
set(CMAKE_CXX_STANDARD_LIBRARIES_INIT "-Wl,-Bstatic -lwinpthread")

set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
