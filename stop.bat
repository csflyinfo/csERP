@echo off
title ERP Stop
cd /d "%~dp0"

echo ================================================
echo   Stopping ERP Services
echo ================================================
echo.

echo Stopping Backend (8080)...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
  echo    Kill PID %%a
  taskkill /F /PID %%a >nul 2>&1
)

echo Stopping Frontend (5173)...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5173 " ^| findstr "LISTENING"') do (
  echo    Kill PID %%a
  taskkill /F /PID %%a >nul 2>&1
)

echo Closing windows...
taskkill /F /FI "WINDOWTITLE eq ERP Backend*" >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq ERP Frontend*" >nul 2>&1

echo.
echo ================================================
echo   All services stopped
echo ================================================
echo.
timeout /t 3 /nobreak >nul
