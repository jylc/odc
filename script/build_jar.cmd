@echo off
rem ===========================================================================
rem build jar only, for backend debug scenario
rem Windows equivalent of build_jar.sh
rem will copy executable jar to %ODC_DIR%\lib, so start-odc.sh is able to find jar
rem
rem usage: script\build_jar.cmd [extra maven args...]
rem   extra maven args are passed through to maven, e.g. -Pci -Dskip npm build
rem
rem exit code: 0 success, 3 maven build failed, 4 copy artifacts failed,
rem            5 zip package failed
rem ===========================================================================
setlocal EnableExtensions

rem project root = parent directory of this script
set "ODC_DIR=%~dp0.."

rem resolve maven command: env override > project wrapper mvnw.cmd > mvn on PATH
rem NOTE: keep this as sequential flow (goto), do NOT wrap in a parenthesized block:
rem %VAR% inside a block expands at parse time, so a set followed by %VAR% in the
rem same block would read the stale (empty) value.
if defined MAVEN_CMD goto :maven_resolved
set "MAVEN_CMD=%ODC_DIR%\mvnw.cmd"
if exist "%MAVEN_CMD%" goto :maven_resolved
set "MAVEN_CMD=mvn"
:maven_resolved

rem all script args are passed to maven as extra args, same as build_jar.sh
set "MAVEN_EXTRA_ARGS=%*"

echo [INFO] ODC_DIR=%ODC_DIR%
echo [INFO] MAVEN_CMD=%MAVEN_CMD%
if defined MAVEN_EXTRA_ARGS echo [INFO] maven_extra_args: %MAVEN_EXTRA_ARGS%

rem ---------------------------------------------------------------------------
rem maven build, same as maven_build_jar() in functions.sh
rem ---------------------------------------------------------------------------
pushd "%ODC_DIR%"
rem warm up maven, failure is tolerated the same way as functions.sh
call "%MAVEN_CMD%" help:system %MAVEN_EXTRA_ARGS%

call "%MAVEN_CMD%" clean install -Dmaven.test.skip=true %MAVEN_EXTRA_ARGS%
if errorlevel 1 (
    popd
    echo [ERROR] maven build jar failed
    exit /b 3
)
popd
echo [INFO] maven build jar success, copy executable jar to %ODC_DIR%\lib for use script/start-odc.sh locally.

rem ---------------------------------------------------------------------------
rem prepare target directories: lib, conf, plugins, starters, modules
rem ---------------------------------------------------------------------------
for %%D in (lib conf plugins starters modules) do (
    if not exist "%ODC_DIR%\%%D" mkdir "%ODC_DIR%\%%D"
)

rem ---------------------------------------------------------------------------
rem copy executable jar and log4j2 configs
rem ---------------------------------------------------------------------------
if exist "%ODC_DIR%\lib\*.jar" del /f /q "%ODC_DIR%\lib\*.jar"
copy /y "%ODC_DIR%\server\odc-server\target\odc-*-executable.jar" "%ODC_DIR%\lib\"
if errorlevel 1 goto :copy_failed
copy /y "%ODC_DIR%\server\odc-server\target\classes\log4j2.xml" "%ODC_DIR%\conf\"
if errorlevel 1 goto :copy_failed
copy /y "%ODC_DIR%\server\odc-server\target\classes\log4j2-task.xml" "%ODC_DIR%\conf\"
if errorlevel 1 goto :copy_failed

rem ---------------------------------------------------------------------------
rem copy plugin jars
rem ---------------------------------------------------------------------------
echo [INFO] copy plugin jars to %ODC_DIR%\plugins .
if exist "%ODC_DIR%\plugins\*.jar" del /f /q "%ODC_DIR%\plugins\*.jar"
if exist "%ODC_DIR%\distribution\plugins\*.jar" (
    copy /y "%ODC_DIR%\distribution\plugins\*.jar" "%ODC_DIR%\plugins\"
    if errorlevel 1 goto :copy_failed
) else (
    echo [WARN] no plugin jars found in distribution\plugins, skip.
)

rem ---------------------------------------------------------------------------
rem copy starter jars
rem ---------------------------------------------------------------------------
echo [INFO] copy starter jars to %ODC_DIR%\starters .
if exist "%ODC_DIR%\starters\*.jar" del /f /q "%ODC_DIR%\starters\*.jar"
if exist "%ODC_DIR%\distribution\starters\*.jar" (
    copy /y "%ODC_DIR%\distribution\starters\*.jar" "%ODC_DIR%\starters\"
    if errorlevel 1 goto :copy_failed
) else (
    echo [WARN] no starter jars found in distribution\starters, skip.
)

rem ---------------------------------------------------------------------------
rem copy module jars
rem ---------------------------------------------------------------------------
echo [INFO] copy modules jars to %ODC_DIR%\modules .
if exist "%ODC_DIR%\modules\*.jar" del /f /q "%ODC_DIR%\modules\*.jar"
if exist "%ODC_DIR%\distribution\modules\*.jar" (
    copy /y "%ODC_DIR%\distribution\modules\*.jar" "%ODC_DIR%\modules\"
    if errorlevel 1 goto :copy_failed
) else (
    echo [WARN] no module jars found in distribution\modules, skip.
)

rem ---------------------------------------------------------------------------
rem package lib/plugins/starters into a versioned zip (name derived from the
rem executable jar, e.g. odc-server-4.3.4-SNAPSHOT-executable.jar -> odc-4.3.4-SNAPSHOT.zip)
rem ---------------------------------------------------------------------------
set "ZIP_BASE="
for %%F in ("%ODC_DIR%\lib\odc-*-executable.jar") do set "ZIP_BASE=%%~nF"
if not defined ZIP_BASE (
    echo [ERROR] no executable jar found in lib, cannot determine version for zip package
    exit /b 5
)
set "ZIP_VERSION=%ZIP_BASE:odc-server-=%"
set "ZIP_VERSION=%ZIP_VERSION:-executable=%"
set "ZIP_FILE=%ODC_DIR%\odc-%ZIP_VERSION%.zip"
echo [INFO] package lib/plugins/starters into %ZIP_FILE% .
if exist "%ZIP_FILE%" del /f /q "%ZIP_FILE%"
pushd "%ODC_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path 'lib','plugins','starters' -DestinationPath '%ZIP_FILE%' -Force"
if errorlevel 1 (
    popd
    echo [ERROR] create zip package failed
    exit /b 5
)
popd

echo [INFO] build jar success
exit /b 0

:copy_failed
echo [ERROR] copy jar files failed
exit /b 4
