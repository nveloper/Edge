# Copies a .litertlm model onto the emulator/device where Edge looks for models.
#
#   .\scripts\push-model.ps1 C:\models\gemma-4-E2B-it.litertlm
param([Parameter(Mandatory = $true)][string]$Model)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$pkg = "node.nveloper.edge"
$name = Split-Path $Model -Leaf

# 1) App-specific external storage (adb shell can write here on most builds).
$external = "/sdcard/Android/data/$pkg/files/models"
& $adb shell mkdir -p $external
& $adb push $Model "$external/$name"
if ($LASTEXITCODE -eq 0) { Write-Host "pushed to $external/$name"; exit 0 }

# 2) Fallback: stage in /data/local/tmp and copy into internal storage with run-as (debug builds only).
Write-Host "direct push failed, falling back to run-as"
& $adb push $Model "/data/local/tmp/$name"
& $adb shell run-as $pkg sh -c "mkdir -p files/models && cp /data/local/tmp/$name files/models/$name"
& $adb shell rm "/data/local/tmp/$name"
Write-Host "copied to internal files/models/$name"
