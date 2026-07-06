@echo off
title ERP System Launcher
cd /d "%~dp0"

echo ================================================
echo   ERP-WMS-TMS System Launcher
echo ================================================
echo.

echo [Check] Verifying environment...
where java >nul 2>&1
if errorlevel 1 (echo [ERROR] Java not found & pause & exit /b 1)
echo    Java: OK

where mvn >nul 2>&1
if errorlevel 1 (echo [ERROR] Maven not found & pause & exit /b 1)
echo    Maven: OK

where node >nul 2>&1
if errorlevel 1 (echo [ERROR] Node.js not found & pause & exit /b 1)
echo    Node.js: OK

where npm >nul 2>&1
if errorlevel 1 (echo [ERROR] npm not found & pause & exit /b 1)
echo    npm: OK

if not exist "backend\pom.xml" (echo [ERROR] backend not found & pause & exit /b 1)
if not exist "frontend\package.json" (echo [ERROR] frontend not found & pause & exit /b 1)
echo    Project files: OK
echo.

echo [1/4] Release ports...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
  echo    Kill PID %%a on 8080
  taskkill /F /PID %%a >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":5173 " ^| findstr "LISTENING"') do (
  echo    Kill PID %%a on 5173
  taskkill /F /PID %%a >nul 2>&1
)
echo    Ports OK
echo.

if not exist "frontend\node_modules" (
  echo [Setup] Installing frontend deps, please wait 1-3 min...
  cd frontend
  call npm install
  cd ..
  echo.
)

echo [2/4] Starting Backend in new window...
start "ERP Backend :8080" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"
echo    Backend window opened
echo.

echo [3/4] Waiting backend ready...
set count=0
:WAIT
timeout /t 2 /nobreak >nul
set /a count+=1
curl -s -o nul -w "%%{http_code}" http://localhost:8080/api/actuator/health 2>nul | findstr "200" >nul
if not errorlevel 1 (
  echo    Backend READY!
  goto READY
)
if %count% geq 60 (
  echo    Timeout, but continuing...
  goto READY
)
echo    Waiting... %count%/60
goto WAIT

:READY
echo.

echo [4/4] Starting Frontend in new window...
start "ERP Frontend :5173" cmd /k "cd /d %~dp0frontend && npm run dev"
echo    Frontend window opened
echo.

echo Waiting frontend...
timeout /t 8 /nobreak >nul

echo Opening browser...
start http://localhost:5173

echo.
echo ================================================
echo   System Started!
echo ================================================
echo   URL:      http://localhost:5173
echo   Username: admin
echo   Password: admin123
echo ================================================
echo.
echo Tips:
echo   - Close service windows to stop
echo   - Or run stop.bat
echo.
pause
