@echo off
rem ---------------------------------------------------------------------------
rem bin\jia.cmd - run the jvm-incident-agent CLI from anywhere (cmd.exe).
rem
rem   bin\jia analyze corpus\incident-deadlock
rem   bin\jia rules --format json
rem   bin\jia explain TDA001
rem
rem Same contract as bin/jia: it runs the shaded jar target\jia.jar and builds
rem it once with ..\mvnw.cmd only when the jar is missing.
rem
rem Environment:
rem   JIA_JAR         jar to run.        Default: <repo>\target\jia.jar
rem   JIA_MAVEN_REPO  while building, passed as -Dmaven.repo.local
rem   JIA_MAVEN_HOME  passed through to mvnw.cmd
rem   JAVA_HOME       a JDK/JRE home; otherwise java must be on PATH
rem
rem Exit codes are the CLI's own (0 clean, 1 HIGH/CRITICAL found, 2 unreadable
rem input); 3 means this launcher could not find java or finish the build.
rem
rem Everything is quoted, so the repository may live under a path with spaces.
rem ---------------------------------------------------------------------------

setlocal EnableExtensions EnableDelayedExpansion

set "BIN_DIR=%~dp0"
if "%BIN_DIR:~-1%"=="\" set "BIN_DIR=%BIN_DIR:~0,-1%"
for %%P in ("!BIN_DIR!\..") do set "ROOT=%%~fP"

if defined JIA_JAR (set "JAR=!JIA_JAR!") else set "JAR=%ROOT%\target\jia.jar"

if exist "!JAR!" goto pick_java

echo jia: !JAR! is missing, building it once with %ROOT%\mvnw.cmd 1>&2
if not exist "%ROOT%\mvnw.cmd" (
  echo jia: no %ROOT%\mvnw.cmd to build the jar with 1>&2
  exit /b 3
)
if defined JIA_MAVEN_REPO (
  call "%ROOT%\mvnw.cmd" -q -DskipTests "-Dmaven.repo.local=!JIA_MAVEN_REPO!" package
) else (
  call "%ROOT%\mvnw.cmd" -q -DskipTests package
)
if errorlevel 1 (
  echo jia: the build of target\jia.jar failed 1>&2
  exit /b 3
)
if not exist "!JAR!" (
  echo jia: the build finished but !JAR! is still missing 1>&2
  exit /b 3
)

:pick_java
set "JAVA="
if defined JAVA_HOME (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo jia: JAVA_HOME=%JAVA_HOME% has no bin\java.exe 1>&2
    exit /b 3
  )
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  where java >nul 2>&1
  if errorlevel 1 (
    echo jia: JAVA_HOME is not set and java is not on PATH 1>&2
    exit /b 3
  )
  set "JAVA=java"
)

rem -Dfile.encoding is not optional: without it a JVM on Windows reads the UTF-8
rem corpus and fixtures in the ANSI code page and findings change per machine.
"!JAVA!" -Dfile.encoding=UTF-8 -jar "!JAR!" %*
set "JIA_RC=%ERRORLEVEL%"
endlocal & exit /b %JIA_RC%
