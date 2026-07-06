@echo off
title ERP Diagnostic
cd /d "%~dp0"

echo ================================================
echo   ERP Environment Diagnostic
echo ================================================
echo.
echo Current Dir: %CD%
echo.

echo [1] Check Java
where java
java -version 2>&1
echo.

echo [2] Check Maven
where mvn
echo.

echo [3] Check Node
where node
node --version
echo.

echo [4] Check npm
where npm
echo.

echo [5] Check project files
if exist "backend\pom.xml" (echo    backend pom.xml FOUND) else (echo    backend pom.xml MISSING)
if exist "frontend\package.json" (echo    frontend package.json FOUND) else (echo    frontend package.json MISSING)
if exist "frontend\node_modules" (echo    node_modules FOUND) else (echo    node_modules NOT INSTALLED)
echo.

echo [6] Check port 8080
netstat -aon | findstr ":8080 " | findstr "LISTENING"
echo.

echo [7] Check port 5173
netstat -aon | findstr ":5173 " | findstr "LISTENING"
echo.

echo ================================================
echo Diagnostic done
echo ================================================
pause
