param(
    [switch]$Force,
    [switch]$Quick
)

# Wrapper for daily_auto.py: runs it and captures UTF-8 logs.

$Tools = 'D:\Walls\tools'
$LogDir = Join-Path $Tools 'logs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $LogDir "daily-$stamp.log"

$py = 'C:\Users\szech\AppData\Local\Python\bin\python.exe'
if (-not (Test-Path $py)) {
    Write-Host "Python not found: $py"
    exit 2
}

$env:PYTHONIOENCODING = 'utf-8'
$pyArgs = @('-u', (Join-Path $Tools 'daily_auto.py'))
if ($Force) { $pyArgs += '--force' }
if ($Quick) { $pyArgs += '--quick' }

$p = Start-Process -FilePath $py -ArgumentList $pyArgs `
    -WindowStyle Hidden -WorkingDirectory $Tools `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
    -PassThru -Wait
$code = $p.ExitCode

# Clean up old logs, keep latest 30
Get-ChildItem -Path $LogDir -Filter 'daily-*.log*' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 30 |
    Remove-Item -Force -ErrorAction SilentlyContinue

if ($code -eq 0) {
    Write-Host "daily_auto done. Log: $log"
} else {
    Write-Host "daily_auto failed (exit $code). Log: $log"
}
exit $code
