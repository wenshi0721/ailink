@echo off
chcp 65001 >nul
title AiLink - Allow LAN access to NapCat (needs admin)

net session >nul 2>&1
if errorlevel 1 (
  echo Requesting administrator privileges...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b 0
)

echo Adding inbound rules for TCP 3001 (OneBot WS) and 6099 (WebUI)...
netsh advfirewall firewall delete rule name="AiLink OneBot WS 3001" >nul 2>&1
netsh advfirewall firewall delete rule name="AiLink NapCat WebUI 6099" >nul 2>&1
netsh advfirewall firewall add rule name="AiLink OneBot WS 3001" dir=in action=allow protocol=TCP localport=3001 profile=any
netsh advfirewall firewall add rule name="AiLink NapCat WebUI 6099" dir=in action=allow protocol=TCP localport=6099 profile=any

echo.
echo Current rules:
netsh advfirewall firewall show rule name="AiLink OneBot WS 3001"
netsh advfirewall firewall show rule name="AiLink NapCat WebUI 6099"
echo.
echo Done. Phones on the same LAN can now reach ws://^<PC-IP^>:3001
echo.
pause
