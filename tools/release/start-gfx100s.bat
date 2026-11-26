@echo off
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if exist "%ROOT%\jdk\bin\java.exe" (
    set "JAVA_HOME=%ROOT%\jdk"
    goto :run
)
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" goto :run
)
where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: Bundled JDK 25 ^(jdk\bin\java.exe^) not found and no system JDK 25 is installed.
    echo Please install JDK 25 or set JAVA_HOME, then double-click start-gfx100s.bat again.
    pause
    exit /b 1
)

:run
if not defined FUJI_RENDER_LIBRARY (
    if exist "%ROOT%\runtime\native\fuji_render.dll" (
        set "FUJI_RENDER_LIBRARY=%ROOT%\runtime\native\fuji_render.dll"
    )
)
if not defined FUJI_VULKAN_OPTICS_LIBRARY (
    if exist "%ROOT%\runtime\native\fuji_vulkan_optics.dll" (
        set "FUJI_VULKAN_OPTICS_LIBRARY=%ROOT%\runtime\native\fuji_vulkan_optics.dll"
    )
)
if not defined FUJI_ISP_SIMD_LIBRARY (
    if exist "%ROOT%\runtime\native\fuji_isp_simd.dll" (
        set "FUJI_ISP_SIMD_LIBRARY=%ROOT%\runtime\native\fuji_isp_simd.dll"
    )
)
if not defined FUJI_CODEC_LIBRARY (
    if exist "%ROOT%\runtime\native\fuji_codec.dll" (
        set "FUJI_CODEC_LIBRARY=%ROOT%\runtime\native\fuji_codec.dll"
    )
)
if not defined FUJI_GAMEPAD_LIBRARY (
    if exist "%ROOT%\runtime\native\fuji_gamepad.dll" (
        set "FUJI_GAMEPAD_LIBRARY=%ROOT%\runtime\native\fuji_gamepad.dll"
    )
)
if not defined DESKTOP_OPTS (
    set "DESKTOP_OPTS=-Dprism.order=d3d,sw"
)
if exist "%ROOT%\runtime\fuji-emulator.aot" (
    set "DESKTOP_OPTS=%DESKTOP_OPTS% -XX:AOTCache=%ROOT%\runtime\fuji-emulator.aot"
)
call "%ROOT%\bin\desktop.bat" --home "%ROOT%" --config "%ROOT%\configs\emulator.yaml" --auto-boot %*
if errorlevel 1 (
    echo.
    echo Emulator exited with error code %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)
endlocal
