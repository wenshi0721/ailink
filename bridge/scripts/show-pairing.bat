@echo off
REM ==================================================================
REM  Show AiLink pairing QR codes (QQ / WeChat bridges on this machine)
REM  Generates release\pairing.html and opens it in the default browser.
REM ==================================================================
setlocal
cd /d "%~dp0..\.."

where node >nul 2>&1
if errorlevel 1 (
  echo [ERR] node not found in PATH.
  echo        Install Node.js or use the node bundled with NapCat.
  pause
  exit /b 1
)

node tools\pairing\make-pairing.mjs
if errorlevel 1 (
  echo [ERR] make-pairing.mjs failed.
  pause
  exit /b 1
)

if exist "release\pairing.html" (
  start "" "release\pairing.html"
) else (
  echo [WARN] release\pairing.html not found.
)

echo.
echo Pairing page opened. Scan the QR code with the phone camera.
pause
