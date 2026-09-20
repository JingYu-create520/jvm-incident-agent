@echo off
rem ---------------------------------------------------------------------------
rem mvnw.cmd - Maven launcher for jvm-incident-agent (cmd.exe counterpart of
rem ./mvnw; read that header first, the contract is identical).
rem
rem Hand-written because the stock maven-wrapper 3.3.4 batch script unpacks its
rem distribution into %USERPROFILE%\.m2\wrapper\dists - outside the repository.
rem This launcher keeps the cache inside the project and prefers an installed
rem Maven:
rem
rem   JIA_MAVEN_HOME  Maven home to use as-is (must contain bin\mvn.cmd). While
rem                   it is set nothing is downloaded, so an offline machine
rem                   only has to point at a directory once.
rem   JIA_MAVEN_CACHE Where a downloaded distribution is unpacked.
rem                   Default: <this directory>\.mvn\wrapper\dists
rem
rem Resolution order: JIA_MAVEN_HOME, then the cached distribution, then a
rem download of distributionUrl from .mvn\wrapper\maven-wrapper.properties
rem (SHA-256 checked against distributionSha256Sum when present, unpacked with
rem PowerShell). No maven-wrapper.jar is needed.
rem
rem MAVEN_OPTS always carries -Dfile.encoding=UTF-8: a JVM on Windows defaults
rem to the ANSI code page (GBK on the machine this project was built on) while
rem the corpus, the fixtures and the sources are UTF-8.
rem ---------------------------------------------------------------------------

setlocal EnableExtensions EnableDelayedExpansion

set "PRG_DIR=%~dp0"
if "%PRG_DIR:~-1%"=="\" set "PRG_DIR=%PRG_DIR:~0,-1%"
set "PROPS=%PRG_DIR%\.mvn\wrapper\maven-wrapper.properties"
if not defined JIA_MAVEN_CACHE set "JIA_MAVEN_CACHE=%PRG_DIR%\.mvn\wrapper\dists"
set "MAVEN_PROJECTBASEDIR=%PRG_DIR%"

set "MO_CHECK=%MAVEN_OPTS%"
if not defined MO_CHECK (
  set "MAVEN_OPTS=-Dfile.encoding=UTF-8"
) else (
  echo !MO_CHECK!| findstr /c:"-Dfile.encoding" >nul 2>&1
  if errorlevel 1 set "MAVEN_OPTS=!MO_CHECK! -Dfile.encoding=UTF-8"
)

set "MVN_CMD="

if defined JIA_MAVEN_HOME (
  set "JMH=%JIA_MAVEN_HOME%"
  if "!JMH:~-1!"=="\" set "JMH=!JMH:~0,-1!"
  if not exist "!JMH!\bin\mvn.cmd" (
    echo mvnw.cmd: JIA_MAVEN_HOME=%JIA_MAVEN_HOME% has no bin\mvn.cmd 1>&2
    exit /b 1
  )
  set "MVN_CMD=!JMH!\bin\mvn.cmd"
  goto run
)

if not exist "%PROPS%" (
  echo mvnw.cmd: JIA_MAVEN_HOME is not set and %PROPS% is missing 1>&2
  exit /b 1
)

set "DIST_URL="
set "DIST_SHA="
for /f "usebackq eol=# tokens=1,* delims==" %%K in ("%PROPS%") do (
  if /i "%%K"=="distributionUrl" set "DIST_URL=%%L"
  if /i "%%K"=="distributionSha256Sum" set "DIST_SHA=%%L"
)
if not defined DIST_URL (
  echo mvnw.cmd: no distributionUrl in %PROPS% 1>&2
  exit /b 1
)

for %%F in ("!DIST_URL!") do set "DIST_NAME=%%~nxF"
set "DIST_NAME=!DIST_NAME:.zip=!"
set "DIST_NAME=!DIST_NAME:-bin=!"
set "DIST_HOME=%JIA_MAVEN_CACHE%\!DIST_NAME!"

if exist "!DIST_HOME!\bin\mvn.cmd" (
  set "MVN_CMD=!DIST_HOME!\bin\mvn.cmd"
  goto run
)

echo mvnw.cmd: !DIST_HOME! is not unpacked yet
echo mvnw.cmd: downloading !DIST_URL!
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; $url='!DIST_URL!'; $zip='!DIST_HOME!.zip'; $dst='!JIA_MAVEN_CACHE!'; $sha='!DIST_SHA!'; $null=New-Item -ItemType Directory -Force -Path $dst; $null=Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing; if ($sha) { $h=(Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower(); if ($h -ne $sha.ToLower()) { Write-Host ('mvnw.cmd: checksum mismatch for ' + $zip + ': expected ' + $sha + ', got ' + $h); exit 1 } }; $null=Expand-Archive -LiteralPath $zip -DestinationPath $dst -Force; $null=Remove-Item -LiteralPath $zip -Force"
if errorlevel 1 (
  echo mvnw.cmd: could not fetch/unpack !DIST_URL! - set JIA_MAVEN_HOME to a local Maven home 1>&2
  exit /b 1
)

if exist "!DIST_HOME!\bin\mvn.cmd" (
  set "MVN_CMD=!DIST_HOME!\bin\mvn.cmd"
) else (
  rem a distribution that unpacked into a differently named directory
  for /d %%D in ("!DIST_HOME!\*") do if exist "%%~fD\bin\mvn.cmd" set "MVN_CMD=%%~fD\bin\mvn.cmd"
)
if not defined MVN_CMD (
  echo mvnw.cmd: downloaded distribution has no bin\mvn.cmd under !DIST_HOME! 1>&2
  exit /b 1
)

:run
if not defined JAVA_HOME (
  where java >nul 2>&1
  if errorlevel 1 (
    echo mvnw.cmd: JAVA_HOME is not set and there is no java on PATH 1>&2
    exit /b 1
  )
)

call "%MVN_CMD%" -Dmaven.multiModuleProjectDirectory="%PRG_DIR%" %*
set "JIA_RC=%ERRORLEVEL%"
endlocal & exit /b %JIA_RC%
