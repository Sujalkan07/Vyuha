@echo off
setlocal EnableDelayedExpansion

set "MVN_VERSION=3.9.9"
set "MVN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVN_VERSION%/apache-maven-%MVN_VERSION%-bin.zip"
set "WRAPPER_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MVN_VERSION%"
set "MVN_HOME=%WRAPPER_DIR%\apache-maven-%MVN_VERSION%"
set "MVN_CMD=%MVN_HOME%\bin\mvn.cmd"

if not exist "%MVN_CMD%" (
    echo [mvnw] Downloading Apache Maven %MVN_VERSION%...
    if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
    set "ZIP_FILE=%WRAPPER_DIR%\apache-maven-%MVN_VERSION%-bin.zip"
    powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MVN_URL%' -OutFile '!ZIP_FILE!'; Expand-Archive -Path '!ZIP_FILE!' -DestinationPath '%WRAPPER_DIR%' -Force; Remove-Item '!ZIP_FILE!' -Force"
    if errorlevel 1 (
        echo [mvnw] Failed to download Apache Maven %MVN_VERSION%.
        exit /b 1
    )
)

call "%MVN_CMD%" %*
exit /b %ERRORLEVEL%
