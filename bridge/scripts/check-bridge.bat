@echo off
chcp 65001 >nul
title AiLink - OneBot bridge self-check

REM 用法：
REM   check-bridge.bat                         本机自检
REM   check-bridge.bat ws://192.168.1.10:3001 <token>   指定地址

where node >nul 2>&1
if errorlevel 1 (
  echo [ERR] node not found in PATH. Install Node.js or use the bundled node.
  pause & exit /b 1
)

node "%~dp0test-onebot.mjs" %*
echo.
pause
