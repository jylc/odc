# ODC backend stop script (PowerShell), companion to start-odc-mysql.ps1
# Usage: powershell -ExecutionPolicy Bypass -File stop-odc-mysql.ps1
#
# Stops the ODC server process started by start-odc-mysql.ps1.
# It finds the process by the odc-server jar path in its command line,
# so it works regardless of PID (e.g. after a restart).

$ErrorActionPreference = "Continue"
$ProjectRoot = "E:\Projects\Java\odc"
$JarFile = "$ProjectRoot\server\odc-server\target\odc-server-4.3.4-SNAPSHOT-executable.jar"

Write-Host "=== ODC stop ===" -ForegroundColor Cyan

# Find ODC java process by jar path in command line
$Target = $JarFile -replace '\\', '\'
$Procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$Target*" }

if (-not $Procs) {
    Write-Host "[INFO] no running ODC process found (jar: $JarFile)" -ForegroundColor Yellow
    exit 0
}

foreach ($p in $Procs) {
    Write-Host "Stopping PID $($p.ProcessId) ..." -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Seconds 2

# Verify stopped
$Remaining = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$Target*" }
if ($Remaining) {
    Write-Host "[FAIL] process still running: $($Remaining.ProcessId)" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] ODC process stopped" -ForegroundColor Green
Write-Host ""
Write-Host "=== Stop done ==="