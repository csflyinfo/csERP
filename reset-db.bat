@echo off
title ERP Reset DB
cd /d "%~dp0"

echo ================================================
echo   Reset Database
echo ================================================
echo.
echo WARNING: All business data will be cleared
echo Only admin/admin123 will remain
echo.
set /p confirm=Continue? (Y/N):
if /i not "%confirm%"=="Y" (
  echo Cancelled
  timeout /t 2 /nobreak >nul
  exit /b
)

echo.
echo [1/3] Stop backend...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
  taskkill /F /PID %%a >nul 2>&1
)
timeout /t 2 /nobreak >nul

echo [2/3] Delete DB files...
if exist "backend\data\erp-v1.mv.db" (
  del /F /Q "backend\data\erp-v1.mv.db"
  echo    erp-v1.mv.db deleted
)
if exist "backend\data\erp-v1.trace.db" (
  del /F /Q "backend\data\erp-v1.trace.db"
  echo    erp-v1.trace.db deleted
)

echo [3/3] Done
echo.
echo ================================================
echo   Database reset. Restart to init.
echo ================================================
echo.
timeout /t 3 /nobreak >nul
