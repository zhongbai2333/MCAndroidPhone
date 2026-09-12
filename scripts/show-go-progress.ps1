param([switch]$Once, [ValidateRange(5,300)][int]$IntervalSeconds=15, [ValidateSet('initial','defaults')][string]$Build='initial')
$ErrorActionPreference='Stop'
$taskEvidence = if ($Build -eq 'defaults') { 'windows-go-defaults-20260912' } else { 'android-go-20260909' }
$taskService = if ($Build -eq 'defaults') { 'mcphone-go-welcome-safe-20260912' } else { 'mcandroidphone-go-20260909' }
$probe = @"
import json,subprocess
from pathlib import Path
root=Path('/home/zhongbai233/android/lineage-23.2-mcphone')
evidence=Path('/home/zhongbai233/src/MCAndroidPhone-handoff-20260909/.runtime/evidence/$taskEvidence')
paths=subprocess.run(['repo','list','-a','-p'],cwd=root,capture_output=True,text=True,check=True,timeout=45).stdout.splitlines()
n=sum((root/'.repo/projects'/(p+'.git')/'index').exists() for p in paths)
state=subprocess.run(['systemctl','show','$taskService','-p','ActiveState','-p','SubState','-p','MemoryCurrent','-p','MemorySwapCurrent'],capture_output=True,text=True,timeout=10).stdout.strip().replace('\n','  ')
def tail(path):
    if not path.exists(): return []
    with path.open('rb') as f:
        f.seek(0,2)
        f.seek(max(0,f.tell()-8192))
        return f.read().decode('utf-8',errors='replace').splitlines()[-5:]
build=evidence/'go-amd64-build.log'
print(json.dumps(dict(total=len(paths),done=n,state=state,stage=tail(evidence/'pipeline-status.log'),log=tail(build if build.exists() and build.stat().st_size else evidence/'repo-full-sync.log'))))
"@
$Host.UI.RawUI.WindowTitle='MCAndroidPhone - Android Go Progress'
while ($true) {
    try {
        # Build logs belong to the root-owned supervisor; this probe only reads them.
        $raw=$probe | & wsl.exe -d Ubuntu-24.04 -u root -- python3 -
        if ($LASTEXITCODE -ne 0) { throw 'Status query failed. Previous results are stale.' }
        $s=($raw | Out-String) | ConvertFrom-Json
        if (-not $Once) { Clear-Host }
        $percent=if ($s.total -gt 0) { [Math]::Round(100*$s.done/$s.total,1) } else { 0 }
        $filled=[Math]::Min(40,[Math]::Floor($percent*0.4))
        $bar=('#'*$filled)+('-'*(40-$filled))
        Write-Host 'MCAndroidPhone - Android Go Progress' -ForegroundColor Cyan
        Write-Host (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        Write-Host "[$bar] $percent%" -ForegroundColor Green
        Write-Host ('Checked out: {0}/{1}   Remaining: {2}' -f $s.done,$s.total,($s.total-$s.done))
        Write-Host 'Repository count only, NOT byte progress or full sync verification.'
        Write-Host ''
        Write-Host $s.state -ForegroundColor Yellow
        Write-Host 'Latest recorded stage:'
        $s.stage | Select-Object -Last 1 | ForEach-Object { Write-Host $_ }
        Write-Host ''
        Write-Host 'Recent log:'
        $s.log | ForEach-Object { Write-Host $_ }
        Write-Host ''
        Write-Host "Refresh: ${IntervalSeconds}s. Close this viewer or Ctrl+C to stop viewing."
        Write-Host 'Closing this viewer does not stop the build.'
    } catch {
        Write-Host $_.Exception.Message -ForegroundColor Red
        if ($Once) { throw }
    }
    if ($Once) { break }
    Start-Sleep -Seconds $IntervalSeconds
}