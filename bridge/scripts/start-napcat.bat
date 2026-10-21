@echo off
chcp 65001 >nul
setlocal EnableExtensions
title NapCat Shell - AiLink Bridge

REM ==================================================================
REM  Start NapCat (OneBot v11 bridge) for AiLink
REM  - Bypasses NapCat's own launcher registry lookup, because a
REM    winget-installed QQ does not register Uninstall\QQ.
REM  - Console output is mirrored to bridge\logs\napcat.log
REM ==================================================================

set "ROOT=%~dp0.."
pushd "%ROOT%\napcat\napcat" || (echo [ERR] NapCat directory not found & pause & exit /b 1)

set "NAPCAT_PATCH_PACKAGE=%cd%\qqnt.json"
set "NAPCAT_LOAD_PATH=%cd%\loadNapCat.js"
set "NAPCAT_INJECT_PATH=%cd%\NapCatWinBootHook.dll"
set "NAPCAT_LAUNCHER_PATH=%cd%\NapCatWinBootMain.exe"
set "NAPCAT_MAIN_PATH=%cd%\napcat.mjs"
set "QQ_PATH=C:\Program Files\Tencent\QQNT\QQ.exe"

if not exist "%QQ_PATH%" (
  echo [ERR] QQ.exe not found at: %QQ_PATH%
  echo       Install QQ NT first, or edit QQ_PATH in this script.
  pause & exit /b 1
)

tasklist /FI "IMAGENAME eq QQ.exe" 2>nul | find /I "QQ.exe" >nul
if not errorlevel 1 (
  echo [WARN] QQ.exe is already running. NapCat needs exclusive launch.
  echo        Quit QQ from the tray first, or run stop-napcat.bat.
  echo.
  pause & exit /b 1
)

echo ============================================================
echo   QQ        : %QQ_PATH%
echo   NapCat    : %cd%
echo   OneBot WS : ws://0.0.0.0:3001   (forward WebSocket)
echo   WebUI     : http://127.0.0.1:6099/webui
echo ============================================================
echo   Scan the QR code printed below with your phone QQ app.
echo   QR is also saved to: napcat\cache\qrcode.png
echo ============================================================
echo.

"%NAPCAT_LAUNCHER_PATH%" "%QQ_PATH%" "%NAPCAT_INJECT_PATH%" %*

set "CODE=%ERRORLEVEL%"
echo.
echo [NapCat exited] code=%CODE%
popd
pause
exit /b %CODE%
