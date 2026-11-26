cmake_minimum_required(VERSION 3.20)
project(fuji_dynarmic LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if (NOT DEFINED DYNARMIC_SOURCE_DIR)
    get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
    set(DYNARMIC_SOURCE_DIR "${REPO_ROOT}/.tools/dynarmic-v1/source" CACHE PATH "Path to pinned Dynarmic upstream source")
endif()

if (NOT EXISTS "${DYNARMIC_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Dynarmic source not found at ${DYNARMIC_SOURCE_DIR}. Run build_dynarmic.sh or pass -DDYNARMIC_SOURCE_DIR=...")
endif()

option(FUJI_DYNARMIC_BUILD_TESTS "Build standalone C++ self-test executable" ON)
option(FUJI_DYNARMIC_ENABLE_LTO "Enable ThinLTO / GCC LTO for Release builds" ON)

if (FUJI_DYNARMIC_ENABLE_LTO AND CMAKE_BUILD_TYPE STREQUAL "Release")
    include(CheckIPOSupported)
    check_ipo_supported(RESULT FUJI_IPO_SUPPORTED OUTPUT FUJI_IPO_ERROR)
    if (FUJI_IPO_SUPPORTED)
        if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
            add_compile_options(-flto=thin)
            add_link_options(-flto=thin)
        elseif (CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
            set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)
        endif()
    else()
        message(STATUS "LTO not supported by toolchain: ${FUJI_IPO_ERROR}")
    endif()
endif()

# Configure Dynarmic: A32-only + generic A32 system runtime (dynarmic::a32_sys), bundled externals, no upstream tests
set(DYNARMIC_FRONTENDS "A32" CACHE STRING "" FORCE)
set(DYNARMIC_BUILD_A32_SYS ON CACHE BOOL "" FORCE)
set(DYNARMIC_TESTS OFF CACHE BOOL "" FORCE)
set(DYNARMIC_USE_BUNDLED_EXTERNALS ON CACHE BOOL "" FORCE)
set(DYNARMIC_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(DYNARMIC_USE_PRECOMPILED_HEADERS OFF CACHE BOOL "" FORCE)
set(DYNARMIC_ENABLE_CPU_FEATURE_DETECTION ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

if (MINGW)
    add_compile_definitions(XBYAK_CPU_CACHE=0)
endif()

find_package(Boost 1.57 REQUIRED)

add_subdirectory("${DYNARMIC_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/dynarmic_sub" EXCLUDE_FROM_ALL)

# The pinned x86 emitter implements per-size alignment checks only on its page-table
# path. Select that path for checked widths and retain native fastmem for other loads
# and stores. Build a private include overlay so both targets can share a clean checkout.
set(memory_emitter "dynarmic/backend/x64/emit_x64_memory.cpp.inc")
set(upstream_emitter "${DYNARMIC_SOURCE_DIR}/src/${memory_emitter}")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${upstream_emitter}")
file(READ "${upstream_emitter}" emitter_source)
set(marker "const auto fastmem_marker = ShouldFastmem(ctx, inst);")
string(REPLACE "${marker}"
    "const bool checked_width = conf.page_table && (conf.detect_misaligned_access_via_page_table & bitsize);
    const auto fastmem_marker = checked_width ? std::nullopt : ShouldFastmem(ctx, inst);"
    checked_emitter "${emitter_source}")
if(checked_emitter STREQUAL emitter_source)
    message(FATAL_ERROR "Update the checked-memory overlay for the selected Dynarmic revision")
endif()
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/checked-memory/${memory_emitter}"
    CONTENT "${checked_emitter}" @ONLY)
target_include_directories(dynarmic BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/checked-memory")

add_library(fuji_dynarmic SHARED
    fuji_dynarmic.cpp
    fuji_dynarmic.h
    fuji_mmu_tlb.hpp
)

target_compile_definitions(fuji_dynarmic PRIVATE FUJI_DYNARMIC_BUILD_DLL=1)
target_include_directories(fuji_dynarmic
    PUBLIC  "${CMAKE_CURRENT_SOURCE_DIR}"
    PRIVATE "${DYNARMIC_SOURCE_DIR}/src"
            "${DYNARMIC_SOURCE_DIR}/externals/mcl/include"
            "${Boost_INCLUDE_DIRS}"
)
target_link_libraries(fuji_dynarmic PRIVATE dynarmic::a32_sys dynarmic merry::mcl)

if (NOT MSVC)
    target_compile_options(fuji_dynarmic PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter)
endif()

if (WIN32)
    set_target_properties(fuji_dynarmic PROPERTIES
        PREFIX ""
        OUTPUT_NAME "fuji_dynarmic"
    )
    if (MINGW)
        target_link_options(fuji_dynarmic PRIVATE
            -static
            -static-libgcc
            -static-libstdc++
            -Wl,-Bstatic
        )
    endif()
else()
    set_target_properties(fuji_dynarmic PROPERTIES
        OUTPUT_NAME "fuji_dynarmic"
    )
endif()

# GNU ld can hide private static-library symbols. Some COFF and Mach-O linkers
# do not implement --exclude-libs.
if (NOT MSVC)
    include(CheckLinkerFlag)
    check_linker_flag(CXX "LINKER:--exclude-libs,ALL" FUJI_DYNARMIC_LINKER_HAS_EXCLUDE_LIBS)
    if (FUJI_DYNARMIC_LINKER_HAS_EXCLUDE_LIBS)
        target_link_options(fuji_dynarmic PRIVATE "LINKER:--exclude-libs,ALL")
    endif()
endif()

if (FUJI_DYNARMIC_BUILD_TESTS)
    add_executable(test_dynarmic_bridge test_dynarmic_bridge.cpp)
    target_include_directories(test_dynarmic_bridge PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(test_dynarmic_bridge PRIVATE fuji_dynarmic)
    if (NOT MSVC)
        target_compile_options(test_dynarmic_bridge PRIVATE -O2 -Wall -Wextra)
    endif()
    if (MINGW)
        target_link_options(test_dynarmic_bridge PRIVATE -static -static-libgcc -static-libstdc++ -Wl,-Bstatic)
    elseif (NOT WIN32)
        set_target_properties(test_dynarmic_bridge PROPERTIES
            BUILD_RPATH "$ORIGIN"
        )
    endif()
endif()
