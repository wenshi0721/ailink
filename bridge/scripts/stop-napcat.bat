@echo off
chcp 65001 >nul
title Stop NapCat / QQ

echo Stopping QQ.exe and NapCat helper processes...
taskkill /F /IM QQ.exe /T >nul 2>&1
taskkill /F /IM NapCatWinBootMain.exe /T >nul 2>&1

timeout /t 1 >nul
tasklist /FI "IMAGENAME eq QQ.exe" 2>nul | find /I "QQ.exe" >nul
if errorlevel 1 (
  echo All stopped.
) else (
  echo [WARN] QQ.exe is still running. End it manually in Task Manager.
)
echo.
pause
