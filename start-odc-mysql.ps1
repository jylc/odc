# ODC backend startup script (PowerShell), modeled on script/start-odc.sh
# Usage: powershell -ExecutionPolicy Bypass -File start-odc-mysql.ps1
#
# Mapping to start-odc.sh:
#   - DB params via env vars ODC_DATABASE_* (same as start-odc.sh `export`)
#   - launch via -jar odc-server executable fat jar (same as start-odc.sh `-jar $jar_file`)
#   - -Dplugin.dir / -Dstarter.dir / -Dmodule.dir point to distribution/ (same as start-odc.sh)
#   - Set ODC_METADB_TYPE=mysql to switch to MySQL metadb (read by MetadbEnvironmentPostProcessor);
#     unset (default) means OceanBase. Adjust ODC_DATABASE_* to match the target database.

$ErrorActionPreference = "Continue"
$ProjectRoot = "E:\Projects\Java\odc"
$Java = "C:\Users\wxb17\.jdks\dragonwell-1.8.0_472\bin\java.exe"

# Paths (corresponding to start-odc.sh install_directory / *directory vars)
$JarFile    = "$ProjectRoot\server\odc-server\target\odc-server-4.3.4-SNAPSHOT-executable.jar"
$LogConfig  = "$ProjectRoot\server\odc-server\src\main\resources\log4j2.xml"
$LogDir     = "$ProjectRoot\log"
$PluginDir  = "$ProjectRoot\distribution\plugins"
$StarterDir = "$ProjectRoot\distribution\starters"
$ModuleDir  = "$ProjectRoot\distribution\modules"
$WorkDir    = "$ProjectRoot"
$ServerPort = "8990"
$LogFile    = "$LogDir\odc-startup.log"

# Env vars (corresponding to start-odc.sh `export ODC_DATABASE_*`)
# Key: ODC_METADB_TYPE=mysql switches to MySQL; absent or ob means OceanBase
$env:ODC_DATABASE_HOST = "127.0.0.1"
$env:ODC_DATABASE_PORT = "2881"
$env:ODC_DATABASE_NAME = "odc_metadb"
$env:ODC_DATABASE_USERNAME = "odc@test"
$env:ODC_DATABASE_PASSWORD = "odc_pwd_2024"
$env:ODC_PROFILE_MODE = "alipay"
$env:ODC_PROPERTY_ENCRYPTION_PASSWORD = "odc_property_encryption_password"
# ODC_METADB_TYPE 未设置（默认 ob）。如需 MySQL metadb，改为 mysql 并调整 DB 参数。
# Remove-Item Env:ODC_METADB_TYPE -ErrorAction SilentlyContinue

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

# JVM args (trimmed version of start-odc.sh gc/heap/oom/app options)
$JArgs = @(
    "-XX:+UseG1GC",
    "-Xms512m",
    "-Xmx4g",
    "-XX:+ExitOnOutOfMemoryError",
    "-Dfile.encoding=UTF-8",
    "-Dlog4j.configurationFile=$LogConfig",
    "-Dodc.log.directory=$LogDir",
    "-Duser.dir=$WorkDir",
    "-Dplugin.dir=$PluginDir",
    "-Dstarter.dir=$StarterDir",
    "-Dmodule.dir=$ModuleDir",
    "-jar", $JarFile,
    "--server.port=$ServerPort"
)

Write-Host "=== ODC MySQL-mode startup (modeled on start-odc.sh) ===" -ForegroundColor Cyan
Write-Host "Java      : $Java"
Write-Host "Jar       : $JarFile"
Write-Host "METADB    : ODC_METADB_TYPE=$($env:ODC_METADB_TYPE) (MySQL mode)"
Write-Host "DB        : $($env:ODC_DATABASE_HOST):$($env:ODC_DATABASE_PORT)/$($env:ODC_DATABASE_NAME)"
Write-Host "Port      : $ServerPort"
Write-Host "LogFile   : $LogFile"
Write-Host ""

if (-not (Test-Path $JarFile)) {
    Write-Host "[ERROR] jar not found: $JarFile" -ForegroundColor Red
    Write-Host "Run first: ./mvnw -pl server/odc-server -am package -Dmaven.test.skip=true"
    exit 1
}

Write-Host "Starting..."
$Process = Start-Process -FilePath $Java -ArgumentList $JArgs `
    -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err" `
    -PassThru -NoNewWindow
Write-Host "PID: $($Process.Id)"
Write-Host "Waiting for startup (up to 180s, probing port $ServerPort)..."

$Started = $false
for ($i = 0; $i -lt 36; $i++) {
    Start-Sleep -Seconds 5
    if ($Process.HasExited) {
        Write-Host "[FAIL] process exited, exit code: $($Process.ExitCode)" -ForegroundColor Red
        Write-Host "--- stdout last 60 lines ---" -ForegroundColor Yellow
        Get-Content $LogFile -Tail 60 -ErrorAction SilentlyContinue
        Write-Host "--- stderr last 40 lines ---" -ForegroundColor Yellow
        Get-Content "$LogFile.err" -Tail 40 -ErrorAction SilentlyContinue
        exit 1
    }
    $Up = (Test-NetConnection -ComputerName 127.0.0.1 -Port $ServerPort -InformationLevel Quiet -WarningAction SilentlyContinue)
    if ($Up) {
        $Started = $true
        Write-Host "[OK] port $ServerPort is listening, startup done (about $(($i+1)*5) s)" -ForegroundColor Green
        break
    }
    Write-Host "  waiting... ($(($i+1)*5) s)"
}

if (-not $Started) {
    Write-Host "[TIMEOUT] port $ServerPort not listening within 180s" -ForegroundColor Yellow
    Write-Host "--- stdout last 60 lines ---" -ForegroundColor Yellow
    Get-Content $LogFile -Tail 60 -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "=== Startup check done ==="
Write-Host "PID: $($Process.Id) (running)"
Write-Host "Stop: Stop-Process -Id $($Process.Id)"
Write-Host "Tail log: Get-Content $LogFile -Tail 100 -Wait"