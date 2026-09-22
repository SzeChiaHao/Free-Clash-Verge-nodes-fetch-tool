# Setup for AutoFreeNodes:
#   1) Daily task at 08:00  (via schtasks, no admin needed)
#   2) Auto-start at logon  (via Startup folder, no admin, no UAC prompt)
# The Python script has a 6-hour guard to avoid duplicate runs.

$ErrorActionPreference = 'Stop'

$Ps1 = 'D:\Walls\tools\daily_auto.ps1'
if (-not (Test-Path $Ps1)) {
    Write-Host "Script not found: $Ps1" -ForegroundColor Red
    exit 2
}

# ---- 1) Daily 08:00 task ----
$TaskName = 'AutoFreeNodes Daily'
$cmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File D:\Walls\tools\daily_auto.ps1'
schtasks /create /tn $TaskName /tr $cmd /sc daily /st '08:00' /f | Out-Null
Write-Host "Daily task created: $TaskName (08:00)" -ForegroundColor Green

# ---- 2) Logon auto-start via Startup folder (silent, no window) ----
$startup = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'
$vbs = Join-Path $startup 'AutoFreeNodes.vbs'
$vbsContent = 'CreateObject("Wscript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""D:\Walls\tools\daily_auto.ps1""", 0, False'
Set-Content -Path $vbs -Value $vbsContent -Encoding ASCII
Write-Host "Logon auto-start created: $vbs" -ForegroundColor Green

Write-Host ""
Write-Host "Done. The old weekly task 'NoMoreWalls 独立节点抓取' is now redundant;"
Write-Host "you can disable it in Task Scheduler if you want."
