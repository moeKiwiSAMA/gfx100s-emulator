/*
 * win_launcher.c - Zero-dependency Windows x86_64 native launcher for FUJIFILM GFX100S Emulator.
 *
 * Locates the bundled jlink JRE (%ROOT%\jdk\bin\java.exe, falling back to %JAVA_HOME% or PATH)
 * and launches MainKt with the canonical Release JVM flags and arguments.
 *
 * Cross-compiled cleanly on Linux via:
 *   x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -municode -s win_launcher.c -o fuji-emulator-gfx100s.exe
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>
#include <stdio.h>
#include <stdlib.h>

#define MAX_CMD_CHARS 32768

/* Prefer dedicated NVIDIA / AMD GPUs on hybrid-graphics Windows laptops. */
__declspec(dllexport) unsigned long NvOptimusEnablement = 1;
__declspec(dllexport) int AmdPowerXpressRequestHighPerformance = 1;

static int file_exists_w(const wchar_t *path) {
    DWORD attrs = GetFileAttributesW(path);
    return (attrs != INVALID_FILE_ATTRIBUTES) && ((attrs & FILE_ATTRIBUTE_DIRECTORY) == 0);
}

static void append_str(wchar_t *dst, size_t cap, size_t *pos, const wchar_t *src) {
    while (*src && *pos + 1 < cap) {
        dst[(*pos)++] = *src++;
    }
    dst[*pos] = L'\0';
}

static void append_quoted_arg(wchar_t *dst, size_t cap, size_t *pos, const wchar_t *arg) {
    int needs_quotes = (*arg == L'\0');
    const wchar_t *p = arg;
    while (*p) {
        if (*p == L' ' || *p == L'\t' || *p == L'"') {
            needs_quotes = 1;
            break;
        }
        p++;
    }
    if (*pos > 0 && *pos + 1 < cap) {
        dst[(*pos)++] = L' ';
    }
    if (!needs_quotes) {
        append_str(dst, cap, pos, arg);
        return;
    }
    if (*pos + 1 < cap) dst[(*pos)++] = L'"';
    p = arg;
    while (*p) {
        size_t bs = 0;
        while (*p == L'\\') {
            bs++;
            p++;
        }
        if (*p == L'\0') {
            for (size_t i = 0; i < bs * 2 && *pos + 1 < cap; i++) dst[(*pos)++] = L'\\';
            break;
        } else if (*p == L'"') {
            for (size_t i = 0; i < bs * 2 + 1 && *pos + 1 < cap; i++) dst[(*pos)++] = L'\\';
            if (*pos + 1 < cap) dst[(*pos)++] = L'"';
            p++;
        } else {
            for (size_t i = 0; i < bs && *pos + 1 < cap; i++) dst[(*pos)++] = L'\\';
            if (*pos + 1 < cap) dst[(*pos)++] = *p;
            p++;
        }
    }
    if (*pos + 1 < cap) dst[(*pos)++] = L'"';
    dst[*pos] = L'\0';
}

static int starts_with_w(const wchar_t *str, const wchar_t *prefix) {
    while (*prefix) {
        if (*str++ != *prefix++) return 0;
    }
    return 1;
}

static int is_machine_output_mode(int argc, wchar_t **argv) {
    for (int i = 1; i < argc; i++) {
        const wchar_t *arg = argv[i];
        if (wcscmp(arg, L"--probe") == 0 ||
            wcscmp(arg, L"--inspect") == 0 ||
            starts_with_w(arg, L"--query-") ||
            starts_with_w(arg, L"--qualify-") ||
            starts_with_w(arg, L"--observe-")) {
            return 1;
        }
    }
    return 0;
}

static int resolve_java_exe(const wchar_t *root, wchar_t *out, size_t cap) {
    /* 1. Bundled jlink JRE under %ROOT%\jdk\bin\java.exe */
    _snwprintf(out, cap, L"%ls\\jdk\\bin\\java.exe", root);
    out[cap - 1] = L'\0';
    if (file_exists_w(out)) return 1;

    /* 2. jpackage runtime layout under %ROOT%\runtime\bin\java.exe */
    _snwprintf(out, cap, L"%ls\\runtime\\bin\\java.exe", root);
    out[cap - 1] = L'\0';
    if (file_exists_w(out)) return 1;

    /* 3. %JAVA_HOME%\bin\java.exe */
    wchar_t java_home[MAX_PATH];
    DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", java_home, MAX_PATH);
    if (n > 0 && n < MAX_PATH) {
        while (n > 0 && (java_home[n - 1] == L'\\' || java_home[n - 1] == L'/')) {
            java_home[--n] = L'\0';
        }
        _snwprintf(out, cap, L"%ls\\bin\\java.exe", java_home);
        out[cap - 1] = L'\0';
        if (file_exists_w(out)) return 1;
    }

    /* 4. java.exe on PATH */
    if (SearchPathW(NULL, L"java.exe", NULL, (DWORD)cap, out, NULL) > 0) {
        return 1;
    }
    return 0;
}

int wmain(int argc, wchar_t **argv) {
    wchar_t exe_path[MAX_PATH];
    wchar_t root[MAX_PATH];
    wchar_t java_exe[MAX_PATH];
    wchar_t classpath[MAX_PATH + 16];
    wchar_t config_path[MAX_PATH + 32];
    wchar_t aot_path[MAX_PATH + 48];
    wchar_t aot_opt[MAX_PATH + 64];
    wchar_t render_dll_path[MAX_PATH + 48];
    wchar_t render_opt[MAX_PATH + 80];
    wchar_t vulkan_dll_path[MAX_PATH + 64];
    wchar_t vulkan_opt[MAX_PATH + 96];
    wchar_t isp_simd_dll_path[MAX_PATH + 64];
    wchar_t isp_simd_opt[MAX_PATH + 96];
    wchar_t codec_dll_path[MAX_PATH + 64];
    wchar_t codec_opt[MAX_PATH + 96];
    wchar_t gamepad_dll_path[MAX_PATH + 64];
    wchar_t gamepad_opt[MAX_PATH + 96];
    wchar_t home_opt[MAX_PATH + 32];

    DWORD len = GetModuleFileNameW(NULL, exe_path, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) {
        fwprintf(stderr, L"ERROR: Failed to resolve launcher executable path.\n");
        return 1;
    }
    wcsncpy(root, exe_path, MAX_PATH - 1);
    root[MAX_PATH - 1] = L'\0';
    wchar_t *slash = wcsrchr(root, L'\\');
    if (slash) *slash = L'\0';

    if (!resolve_java_exe(root, java_exe, MAX_PATH)) {
        fwprintf(stderr,
                 L"ERROR: Bundled JDK 25 (jdk\\bin\\java.exe) not found and no system Java 25 is installed.\n"
                 L"Please extract the complete release archive or install JDK 25 and set JAVA_HOME.\n");
        return 1;
    }

    wchar_t *cmd = (wchar_t *)calloc(MAX_CMD_CHARS, sizeof(wchar_t));
    if (!cmd) return 1;
    size_t pos = 0;

    _snwprintf(classpath, sizeof(classpath) / sizeof(classpath[0]), L"%ls\\lib\\*", root);
    _snwprintf(config_path, sizeof(config_path) / sizeof(config_path[0]), L"%ls\\configs\\emulator.yaml", root);
    _snwprintf(aot_path, sizeof(aot_path) / sizeof(aot_path[0]), L"%ls\\runtime\\fuji-emulator.aot", root);
    _snwprintf(render_dll_path, sizeof(render_dll_path) / sizeof(render_dll_path[0]), L"%ls\\runtime\\native\\fuji_render.dll", root);
    _snwprintf(vulkan_dll_path, sizeof(vulkan_dll_path) / sizeof(vulkan_dll_path[0]), L"%ls\\runtime\\native\\fuji_vulkan_optics.dll", root);
    _snwprintf(isp_simd_dll_path, sizeof(isp_simd_dll_path) / sizeof(isp_simd_dll_path[0]), L"%ls\\runtime\\native\\fuji_isp_simd.dll", root);
    _snwprintf(codec_dll_path, sizeof(codec_dll_path) / sizeof(codec_dll_path[0]), L"%ls\\runtime\\native\\fuji_codec.dll", root);
    _snwprintf(gamepad_dll_path, sizeof(gamepad_dll_path) / sizeof(gamepad_dll_path[0]), L"%ls\\runtime\\native\\fuji_gamepad.dll", root);
    _snwprintf(home_opt, sizeof(home_opt) / sizeof(home_opt[0]), L"-Dfuji.home=%ls", root);

    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, java_exe);
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"--enable-native-access=ALL-UNNAMED");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-Xms512m");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-Xmx2g");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-XX:+UseG1GC");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-XX:MaxGCPauseMillis=15");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-Dprism.order=d3d,sw");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, home_opt);

    if (GetEnvironmentVariableW(L"FUJI_RENDER_LIBRARY", NULL, 0) == 0 && file_exists_w(render_dll_path)) {
        _snwprintf(render_opt, sizeof(render_opt) / sizeof(render_opt[0]), L"-Dfuji.render.library=%ls", render_dll_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, render_opt);
    }

    if (GetEnvironmentVariableW(L"FUJI_VULKAN_OPTICS_LIBRARY", NULL, 0) == 0 && file_exists_w(vulkan_dll_path)) {
        _snwprintf(vulkan_opt, sizeof(vulkan_opt) / sizeof(vulkan_opt[0]), L"-Dfuji.vulkan.optics.library=%ls", vulkan_dll_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, vulkan_opt);
    }

    if (GetEnvironmentVariableW(L"FUJI_ISP_SIMD_LIBRARY", NULL, 0) == 0 && file_exists_w(isp_simd_dll_path)) {
        _snwprintf(isp_simd_opt, sizeof(isp_simd_opt) / sizeof(isp_simd_opt[0]), L"-Dfuji.isp.simd.library=%ls", isp_simd_dll_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, isp_simd_opt);
    }

    if (GetEnvironmentVariableW(L"FUJI_CODEC_LIBRARY", NULL, 0) == 0 && file_exists_w(codec_dll_path)) {
        _snwprintf(codec_opt, sizeof(codec_opt) / sizeof(codec_opt[0]), L"-Dfuji.codec.library=%ls", codec_dll_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, codec_opt);
    }

    if (GetEnvironmentVariableW(L"FUJI_GAMEPAD_LIBRARY", NULL, 0) == 0 && file_exists_w(gamepad_dll_path)) {
        _snwprintf(gamepad_opt, sizeof(gamepad_opt) / sizeof(gamepad_opt[0]), L"-Dfuji.gamepad.library=%ls", gamepad_dll_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, gamepad_opt);
    }

    if (file_exists_w(aot_path)) {
        _snwprintf(aot_opt, sizeof(aot_opt) / sizeof(aot_opt[0]), L"-XX:AOTCache=%ls", aot_path);
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, aot_opt);
    }

    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"-cp");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, classpath);
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"com.github.nyabanban.fuji.emulator.desktop.MainKt");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"--home");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, root);
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"--config");
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, config_path);
    append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, L"--auto-boot");

    for (int i = 1; i < argc; i++) {
        append_quoted_arg(cmd, MAX_CMD_CHARS, &pos, argv[i]);
    }

    if (!is_machine_output_mode(argc, argv)) {
        fputs("Launching FUJIFILM GFX100S Emulator...\n", stdout);
        fflush(stdout);
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (!CreateProcessW(java_exe, cmd, NULL, NULL, TRUE, 0, NULL, root, &si, &pi)) {
        DWORD err = GetLastError();
        fwprintf(stderr, L"ERROR: Failed to launch %ls (Win32 error %lu).\n", java_exe, (unsigned long)err);
        free(cmd);
        return 1;
    }

    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD exit_code = 0;
    GetExitCodeProcess(pi.hProcess, &exit_code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    free(cmd);
    return (int)exit_code;
}
