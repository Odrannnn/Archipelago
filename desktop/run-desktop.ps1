$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$env:PYTHONPATH = "$PSScriptRoot;$env:PYTHONPATH"
Set-Location $ProjectRoot
$DesktopPython = Join-Path $ProjectRoot ".desktop-venv\Scripts\python.exe"
if (-not (Test-Path $DesktopPython)) { $DesktopPython = "python" }
& $DesktopPython -m archipelago_companion
