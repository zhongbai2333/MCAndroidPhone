[CmdletBinding()]
param(
    [string]$Emulator,
    [string]$Adb,
    [string]$Ffmpeg,
    [string]$Python,
    [string]$AvdHome,
    [string]$Qemu
)
$ErrorActionPreference = 'Stop'

function Find-Executable([string]$Explicit, [string]$Name, [string[]]$Candidates) {
    if ($Explicit) {
        if (!(Test-Path -LiteralPath $Explicit -PathType Leaf)) { throw "Executable not found: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Path }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    return $null
}

$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ }
$Emulator = Find-Executable $Emulator 'emulator.exe' @($sdkRoots | ForEach-Object { Join-Path $_ 'emulator\emulator.exe' })
$Adb = Find-Executable $Adb 'adb.exe' @($sdkRoots | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' })
$Ffmpeg = Find-Executable $Ffmpeg 'ffmpeg.exe' @()
$Python = Find-Executable $Python 'python.exe' @()
if (!$AvdHome) {
    if ($env:ANDROID_AVD_HOME) { $AvdHome = $env:ANDROID_AVD_HOME }
    elseif ($env:ANDROID_USER_HOME) { $AvdHome = Join-Path $env:ANDROID_USER_HOME 'avd' }
    elseif ($env:ANDROID_EMULATOR_HOME) { $AvdHome = Join-Path $env:ANDROID_EMULATOR_HOME 'avd' }
    else { $AvdHome = Join-Path $env:USERPROFILE '.android\avd' }
}

Write-Output 'MCAndroidPhone prerequisite check (no installs or AVD changes; SDK/AVD checks apply to the SDK backend)'
[pscustomobject]@{ Emulator = $Emulator; Adb = $Adb; Ffmpeg = $Ffmpeg; Python = $Python; AvdHome = $AvdHome } | Format-List | Out-String | Write-Output
$missing = @()
foreach ($entry in @{ Emulator = $Emulator; Adb = $Adb; Ffmpeg = $Ffmpeg; Python = $Python }.GetEnumerator()) {
    if (!$entry.Value) { $missing += $entry.Key }
}
if ($Emulator) {
    & $Emulator -version 2>&1 | Select-Object -First 1 | Write-Output
    & $Emulator -accel-check 2>&1 | Write-Output
    $previousAvdHome = $env:ANDROID_AVD_HOME
    try {
        $env:ANDROID_AVD_HOME = $AvdHome
        $avds = @(& $Emulator -list-avds 2>$null | Where-Object { $_ -match '^[A-Za-z0-9_.-]+$' })
    } finally { $env:ANDROID_AVD_HOME = $previousAvdHome }
    if (!$avds.Count) { $missing += 'An Android Virtual Device (AVD)'; Write-Output 'AVDs: none found' }
    $sdk = Split-Path (Split-Path $Emulator -Parent) -Parent
    foreach ($avd in $avds) {
        $ini = Join-Path $AvdHome "$avd.ini"
        $avdDirectory = Join-Path $AvdHome "$avd.avd"
        if (Test-Path -LiteralPath $ini) {
            foreach ($line in Get-Content -LiteralPath $ini) {
                if ($line -match '^path=(.+)$') { $avdDirectory = $Matches[1] }
            }
        }
        $config = @{}
        $configFile = Join-Path $avdDirectory 'config.ini'
        if (Test-Path -LiteralPath $configFile) {
            foreach ($line in Get-Content -LiteralPath $configFile) {
                if ($line -match '^([^#=]+)=(.*)$') { $config[$Matches[1]] = $Matches[2] }
            }
        }
        $systemImage = $config['image.sysdir.1']
        if ($systemImage -and ![IO.Path]::IsPathRooted($systemImage)) { $systemImage = Join-Path $sdk $systemImage }
        $imageExists = $systemImage -and (Test-Path -LiteralPath (Join-Path $systemImage 'system.img'))
        [pscustomobject]@{
            Avd = $avd; Android = $config['target']; Architecture = $config['abi.type']
            Width = $config['hw.lcd.width']; Height = $config['hw.lcd.height']
            Orientation = $config['hw.initialOrientation']; RamMB = $config['hw.ramSize']
            SystemImageExists = [bool]$imageExists
        } | Format-List | Out-String | Write-Output
        if (!$imageExists) { $missing += "System image for $avd" }
    }
}
if ($Python) {
    & $Python --version 2>&1 | Write-Output
    Write-Output 'Stock QEMU display capabilities (Windows launcher auto selects dbus; no VM is started):'
    & $Python -c 'import json,sys; from pathlib import Path; scripts=Path(sys.argv[1]); sys.path[:0]=[str(scripts),str(scripts.parent/"bridge")]; from mcandroid_bridge._launch.qemu_runtime import find_qemu,probe_qemu_display; qemu=find_qemu(scripts.parent,(sys.argv[2] or None) if len(sys.argv)>2 else None); print(json.dumps({"qemu":str(qemu) if qemu else None,"display":probe_qemu_display(qemu)}))' $PSScriptRoot "$Qemu" 2>&1 | Write-Output
    & $Python -c 'import importlib.util,json; print(json.dumps({m:importlib.util.find_spec(m) is not None for m in ("grpc","PIL")},sort_keys=True))' 2>&1 | Write-Output
    Write-Output 'grpc/Pillow missing in the selected Python is expected before bridge setup; use its virtual environment.'
}
if ($Ffmpeg) { & $Ffmpeg -version 2>&1 | Select-Object -First 1 | Write-Output }
$running = @(Get-Process -Name emulator,qemu-system-x86_64,qemu-system-aarch64 -ErrorAction SilentlyContinue)
Write-Output ("Running emulator processes: " + $(if ($running.Count) { ($running | ForEach-Object { "$($_.ProcessName) (PID $($_.Id))" }) -join ', ' } else { 'none' }))
if ($missing.Count) { Write-Warning ('Missing: ' + ($missing -join ', ')) }
else { Write-Output 'Executable, AVD and system-image prerequisites found. See acceleration output and bridge dependencies above.' }
