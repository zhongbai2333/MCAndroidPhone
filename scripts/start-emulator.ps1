[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_.-]*$')][string]$Avd,
    [string]$Emulator,
    [string]$AvdHome,
    [ValidateRange(1024,65535)][int]$GrpcPort = 8554,
    [ValidateRange(5554,5682)][int]$ConsolePort = 5556,
    [ValidateSet('auto','host','software','swiftshader')][string]$Gpu = 'auto',
    [string]$RuntimeDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime'),
    [ValidateRange(1,120)][int]$WaitSeconds = 45
)
$ErrorActionPreference = 'Stop'
if ($ConsolePort % 2) { throw 'ConsolePort must be even; Android uses the following port for ADB.' }
if ($GrpcPort -in @($ConsolePort, ($ConsolePort + 1))) { throw 'gRPC and console/ADB ports must differ.' }
if (!$Emulator) {
    $command = Get-Command 'emulator.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { $Emulator = $command.Path }
    else {
        foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if ($sdk) {
                $candidate = Join-Path $sdk 'emulator\emulator.exe'
                if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Emulator = $candidate; break }
            }
        }
    }
}
if (!$Emulator -or !(Test-Path -LiteralPath $Emulator -PathType Leaf)) { throw 'Android emulator.exe not found. Pass -Emulator or set ANDROID_HOME.' }
$Emulator = (Resolve-Path -LiteralPath $Emulator).Path
if (!$AvdHome) {
    if ($env:ANDROID_AVD_HOME) { $AvdHome = $env:ANDROID_AVD_HOME }
    elseif ($env:ANDROID_USER_HOME) { $AvdHome = Join-Path $env:ANDROID_USER_HOME 'avd' }
    elseif ($env:ANDROID_EMULATOR_HOME) { $AvdHome = Join-Path $env:ANDROID_EMULATOR_HOME 'avd' }
    else { $AvdHome = Join-Path $env:USERPROFILE '.android\avd' }
}
if (!(Test-Path -LiteralPath $AvdHome -PathType Container)) { throw "AVD directory not found: $AvdHome" }
$previousAvdHome = $env:ANDROID_AVD_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
try {
    $env:ANDROID_AVD_HOME = $AvdHome
    $env:ANDROID_SDK_ROOT = Split-Path (Split-Path $Emulator -Parent) -Parent
    $avds = @(& $Emulator -list-avds 2>$null | Where-Object { $_ -match '^[A-Za-z0-9_.-]+$' })
    if (!$Avd) {
        if ($avds.Count -ne 1) { throw ('Pass -Avd. Available AVDs: ' + ($avds -join ', ')) }
        $Avd = $avds[0]
    }
    if ($Avd -notin $avds) { throw "AVD '$Avd' is not listed under $AvdHome." }
    $helpText = & $Emulator -help-grpc-use-token 2>&1 | Out-String
    if ($helpText -notmatch 'grpc.token') { throw 'This emulator does not advertise bearer token auth. Use a compatible version or configure JWT explicitly.' }
    foreach ($port in @($GrpcPort, $ConsolePort, ($ConsolePort + 1))) {
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try { $probe.Start() }
        catch { throw "Local port $port is already occupied. Choose other ports; existing processes will not be stopped." }
        finally { $probe.Stop() }
    }
    New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
    $RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdoutFile = Join-Path $RuntimeDirectory "emulator-$stamp.stdout.log"
    $stderrFile = Join-Path $RuntimeDirectory "emulator-$stamp.stderr.log"
    $arguments = @('-avd', $Avd, '-port', "$ConsolePort", '-grpc', "$GrpcPort", '-grpc-use-token',
        '-read-only', '-no-snapshot-load', '-no-snapshot-save', '-no-window', '-no-audio', '-gpu', $Gpu,
        '-camera-front', 'none', '-camera-back', 'none', '-no-boot-anim')
    $started = Get-Date
    # Start-Process receives only validated/simple arguments; paths are separate named parameters.
    $launcher = Start-Process -FilePath $Emulator -ArgumentList $arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
} finally {
    $env:ANDROID_AVD_HOME = $previousAvdHome
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
}

$discoveryDirectory = Join-Path $env:LOCALAPPDATA 'Temp\avd\running'
$discoveryFile = $null
$emulatorPid = $null
$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    foreach ($file in @(Get-ChildItem -LiteralPath $discoveryDirectory -Filter 'pid_*.ini' -ErrorAction SilentlyContinue)) {
        if ($file.LastWriteTime -lt $started.AddSeconds(-1)) { continue }
        $values = @{}
        foreach ($line in Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue) {
            if ($line -match '^([^#=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
        }
        if ($values['grpc.port'] -eq "$GrpcPort" -and $values['grpc.token'] -and $values['port.serial'] -eq "$ConsolePort") {
            $discoveryFile = $file.FullName
            if ($file.Name -match '^pid_(\d+)') { $emulatorPid = [int]$Matches[1] }
            break
        }
    }
    if ($discoveryFile) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

$state = [ordered]@{
    avd = $Avd; launcher_pid = $launcher.Id; emulator_pid = $emulatorPid
    serial = "emulator-$ConsolePort"; grpc_endpoint = "127.0.0.1:$GrpcPort"
    grpc_discovery_file = $discoveryFile; read_only = $true
    started_utc = $started.ToUniversalTime().ToString('o')
    stdout_log = $stdoutFile; stderr_log = $stderrFile
}
$stateFile = Join-Path $RuntimeDirectory 'emulator.json'
$state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
if (!$discoveryFile) {
    throw "Authenticated gRPC discovery was not ready within $WaitSeconds seconds. Launcher PID $($launcher.Id); inspect $stateFile and the logs. This script does not stop other emulator processes."
}
# Do not print the discovery INI or its bearer credential.
[pscustomobject]$state | Format-List
Write-Output "Runtime state saved to $stateFile"
Write-Output 'gRPC discovery is ready; Android may still be booting. Pass grpc_discovery_file to the bridge.'
