@echo off
SET MAVEN_PROJECTBASEDIR=%~dp0
SET JAVA_CMD=%JAVA_HOME%\bin\java.exe
IF NOT EXIST "%JAVA_CMD%" (
  SET JAVA_CMD=java
)
"%JAVA_CMD%" -jar "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" %*
