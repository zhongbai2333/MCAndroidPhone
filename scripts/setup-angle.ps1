param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($ProjectRoot)
$runtime = Join-Path $root '.runtime/angle'
$stage = Join-Path $runtime ('setup-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$archive = Join-Path $stage 'angle.pkg.tar.zst'
$uri = 'https://repo.msys2.org/mingw/ucrt64/mingw-w64-ucrt-x86_64-angleproject-2.1.r25748.890b5d8f-3-any.pkg.tar.zst'
$expected = 'a2f246213415ff739973218dc2d4b6847f8d3376b3dd2e7218204d82ad4e495c'
Invoke-WebRequest -Uri $uri -OutFile $archive -TimeoutSec 120
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
    throw 'ANGLE archive SHA256 mismatch; runtime was not changed.'
}
& tar -xf $archive -C $stage ucrt64/bin/libEGL.dll ucrt64/bin/libGLESv2.dll ucrt64/share/licenses/angleproject/LICENSE
if ($LASTEXITCODE -ne 0) { throw 'ANGLE extraction failed' }
New-Item -ItemType Directory -Path (Join-Path $runtime 'bin'),(Join-Path $runtime 'licenses') -Force | Out-Null
foreach ($name in @('libEGL.dll','libGLESv2.dll')) {
    Copy-Item -LiteralPath (Join-Path $stage "ucrt64/bin/$name") -Destination (Join-Path $runtime "bin/$name") -Force
}
Copy-Item -LiteralPath (Join-Path $stage 'ucrt64/share/licenses/angleproject/LICENSE') -Destination (Join-Path $runtime 'licenses/ANGLE-LICENSE') -Force
@{ source=$uri; sha256=$expected; license='BSD-3-Clause'; installedAt=[DateTime]::UtcNow.ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'provenance.json') -Encoding utf8
Write-Host "ANGLE ready: $runtime"
Write-Host 'Run: .\test-phone.cmd qemu --qemu-gpu virgl'
