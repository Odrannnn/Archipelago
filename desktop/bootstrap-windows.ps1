$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$VirtualEnvironment = Join-Path $ProjectRoot ".desktop-venv"
python -m venv $VirtualEnvironment
$Python = Join-Path $VirtualEnvironment "Scripts\python.exe"
& $Python -m pip install --upgrade pip
& $Python -m pip install -r (Join-Path $ProjectRoot "requirements.txt") -r (Join-Path $PSScriptRoot "requirements.txt")
Write-Host "Desktop Companion is ready. Run .\desktop\run-desktop.ps1"

