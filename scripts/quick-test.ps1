# Bootstrap only: locate real Python, then let its standard-library launcher own setup and cleanup.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$candidates = @()
if ($env:MCANDROIDPHONE_PYTHON) { $candidates += $env:MCANDROIDPHONE_PYTHON }
foreach ($name in @('python.exe', 'python3.exe')) {
    $candidates += @(Get-Command $name -CommandType Application -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -notmatch '\\Microsoft\\WindowsApps\\' } | ForEach-Object Path)
}
$candidates += @(Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python*\python.exe') -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | ForEach-Object FullName)
$pyLauncher = Get-Command 'py.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($pyLauncher) {
    $resolvedPython = & $pyLauncher.Path -3 -c 'import sys; print(sys.executable)' 2>$null
    if ($LASTEXITCODE -eq 0) { $candidates += $resolvedPython }
}
$selectedPython = $null
foreach ($candidate in $candidates | Select-Object -Unique) {
    if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    & $candidate -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)' 2>$null
    if ($LASTEXITCODE -eq 0) { $selectedPython = $candidate; break }
}
if (!$selectedPython) {
    Write-Host 'Python 3.10+ was not found. Install it once, or set MCANDROIDPHONE_PYTHON to python.exe.'
    exit 1
}
$env:PYTHONUTF8 = '1'
& $selectedPython (Join-Path $PSScriptRoot 'quick-test.py') @args
exit $LASTEXITCODE
