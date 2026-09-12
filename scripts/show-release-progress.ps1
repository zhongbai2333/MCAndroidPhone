param([switch]$Once, [ValidateRange(5,300)][int]$IntervalSeconds=5)
$ErrorActionPreference='Stop'
$probe=@"
import json,re,subprocess
from pathlib import Path
base=Path('/home/zhongbai233/src/MCAndroidPhone-release-20260913/.runtime/evidence/full-release-20260913')
def tail(p,limit=262144):
 if not p.exists():return ''
 with p.open('rb') as f:
  f.seek(0,2);f.seek(max(0,f.tell()-limit));return f.read().decode('utf-8',errors='replace')
status=tail(base/'status.log')
state=subprocess.check_output(['systemctl','show','mcphone-full-release-20260913','-p','ActiveState','-p','Result','-p','MemoryCurrent','-p','MemorySwapCurrent'],text=True)
result={'state':dict(x.split('=',1) for x in state.splitlines() if '=' in x),'stage':status.splitlines()[-1:] or ['Waiting for build supervisor'],'arches':[],'finished':'PIPELINE_EXIT=' in status}
for arch in ['amd64','arm64']:
 text=tail(base/(arch+'-build.log'));matches=re.findall(r'\[\s*(\d+)%\s+(\d+)/(\d+)(?:\s+([^\]]*))?\]',text)
 ok=('ARTIFACTS_PRESERVED_'+arch) in status
 active=('BUILDING_'+arch+'_FULL') in status
 matches=[m for m in matches if int(m[2])>5]
 percent,done,total,eta=matches[-1] if matches else ('0','0','0','')
 result['arches'].append({'arch':arch,'percent':100 if ok else int(percent),'done':done,'total':total,'eta':eta,'status':'Done' if ok else (('Building' if matches else 'Configuring') if active else 'Queued'),'last':text.splitlines()[-1:] or ['']})
print(json.dumps(result))
"@
$Host.UI.RawUI.WindowTitle='MCAndroidPhone - Dual-architecture build progress'
while ($true) {
 try {
  $raw=$probe | & wsl.exe -d Ubuntu-24.04 -u root -- python3 -
  if($LASTEXITCODE -ne 0){throw 'Status query failed; previous progress is stale.'}
  $s=($raw|Out-String)|ConvertFrom-Json
  if(-not $Once){Clear-Host}
  Write-Host 'MCAndroidPhone - Full Android Go build' -ForegroundColor Cyan
  Write-Host (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  Write-Host 'WebView retained | 12 jobs | WSL data on E:'
  Write-Host ''
  foreach($a in $s.arches){
   $n=[Math]::Min(36,[Math]::Floor($a.percent*0.36));$bar=('#'*$n)+('-'*(36-$n))
   Write-Host ('{0,-7} [{1}] {2,3}%  {3}' -f $a.arch,$bar,$a.percent,$a.status) -ForegroundColor Green
   if($a.status -eq 'Building'){Write-Host ('        Steps {0}/{1}  Estimate: {2}' -f $a.done,$a.total,$a.eta)}
  }
  Write-Host ''
  $mem=0.0;$swap=0.0
  if($s.state.MemoryCurrent -match '^\d+$'){$mem=[double]$s.state.MemoryCurrent/1GB}
  if($s.state.MemorySwapCurrent -match '^\d+$'){$swap=[double]$s.state.MemorySwapCurrent/1GB}
  Write-Host ('Supervisor: {0} / {1}    RAM: {2:N1} GiB    Swap: {3:N1} GiB' -f $s.state.ActiveState,$s.state.Result,$mem,$swap) -ForegroundColor Yellow
  $s.stage|ForEach-Object{Write-Host $_}
  Write-Host ''
  Write-Host 'Ninja totals and estimates change during compilation; this is not download progress.'
  Write-Host 'Image packaging, upload and real-device acceptance follow compilation.'
  Write-Host "Refresh: ${IntervalSeconds}s. Closing this window does not stop the build."
  if($s.finished -and -not $Once){Write-Host 'Build supervisor finished. See Codex for packaging/acceptance status.';Read-Host 'Press Enter to close';break}
 }catch{Write-Host $_.Exception.Message -ForegroundColor Red;if($Once){throw}}
 if($Once){break}
 Start-Sleep -Seconds $IntervalSeconds
}