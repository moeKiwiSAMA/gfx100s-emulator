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

# Build private patched copies of selected Dynarmic files. The vendor checkout remains shareable.
get_target_property(dynarmic_sources dynarmic SOURCES)
find_package(Git REQUIRED)
set(overlay "${CMAKE_CURRENT_BINARY_DIR}/checked-memory")
set(inputs "${CMAKE_CURRENT_BINARY_DIR}/checked-memory-inputs")
set(patch "${CMAKE_CURRENT_SOURCE_DIR}/a32-checked-memory.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${patch}")

set(patched_files
    "backend/x64/a32_emit_x64_memory.cpp"
    "backend/x64/emit_x64_memory.cpp.inc"
    "backend/x64/a32_interface.cpp"
    "ir/opt/passes.h"
    "ir/opt/a32_get_set_elimination_pass.cpp"
)
foreach(file IN LISTS patched_files)
    configure_file("${DYNARMIC_SOURCE_DIR}/src/dynarmic/${file}" "${inputs}/dynarmic/${file}" COPYONLY)
endforeach()
execute_process(COMMAND "${GIT_EXECUTABLE}" apply --no-index --whitespace=nowarn "${patch}"
    WORKING_DIRECTORY "${inputs}" COMMAND_ERROR_IS_FATAL ANY)
foreach(file IN LISTS patched_files)
    configure_file("${inputs}/dynarmic/${file}" "${overlay}/dynarmic/${file}" COPYONLY)
endforeach()
target_include_directories(dynarmic BEFORE PRIVATE "${overlay}")

set(patched_arm_emitter FALSE)
set(patched_arm_interface FALSE)
set(patched_gse_pass FALSE)
foreach(source IN LISTS dynarmic_sources)
    if(source MATCHES "(^|/)backend/x64/a32_emit_x64_memory\\.cpp$")
        set_source_files_properties("${DYNARMIC_SOURCE_DIR}/src/dynarmic/backend/x64/a32_emit_x64_memory.cpp"
            TARGET_DIRECTORY dynarmic PROPERTIES HEADER_FILE_ONLY TRUE)
        target_sources(dynarmic PRIVATE "${overlay}/dynarmic/backend/x64/a32_emit_x64_memory.cpp")
        set(patched_arm_emitter TRUE)
    elseif(source MATCHES "(^|/)backend/x64/a32_interface\\.cpp$")
        set_source_files_properties("${DYNARMIC_SOURCE_DIR}/src/dynarmic/backend/x64/a32_interface.cpp"
            TARGET_DIRECTORY dynarmic PROPERTIES HEADER_FILE_ONLY TRUE)
        target_sources(dynarmic PRIVATE "${overlay}/dynarmic/backend/x64/a32_interface.cpp")
        set(patched_arm_interface TRUE)
    elseif(source MATCHES "(^|/)ir/opt/a32_get_set_elimination_pass\\.cpp$")
        set_source_files_properties("${DYNARMIC_SOURCE_DIR}/src/dynarmic/ir/opt/a32_get_set_elimination_pass.cpp"
            TARGET_DIRECTORY dynarmic PROPERTIES HEADER_FILE_ONLY TRUE)
        target_sources(dynarmic PRIVATE "${overlay}/dynarmic/ir/opt/a32_get_set_elimination_pass.cpp")
        set(patched_gse_pass TRUE)
    endif()
endforeach()
if(NOT patched_gse_pass)
    message(FATAL_ERROR "No A32 GetSetElimination pass found in the pinned Dynarmic target")
endif()
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64)$" AND (NOT patched_arm_emitter OR NOT patched_arm_interface))
    message(FATAL_ERROR "No A32 x64 memory emitter/interface found in the pinned Dynarmic target")
endif()

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
