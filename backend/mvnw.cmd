@echo off
@REM Maven Wrapper for Windows: runs the Maven version pinned in .mvn\wrapper\maven-wrapper.properties.
@REM Usage (PowerShell):  .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
setlocal
set "MVNW_DIR=%~dp0.mvn\wrapper"
set "MAVEN_HOME="
for /f "usebackq delims=" %%H in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%MVNW_DIR%\install-maven.ps1"`) do set "MAVEN_HOME=%%H"
if not defined MAVEN_HOME (
  echo Could not resolve the Maven distribution. See the message above. 1>&2
  exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
