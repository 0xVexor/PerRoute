$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Lock = Get-Content (Join-Path $ProjectRoot 'browser/helper/xray/xray.lock.json') -Raw | ConvertFrom-Json
$Asset = $Lock.assets.'windows-x64'
$Destination = Join-Path $ProjectRoot 'tools/xray'
$Archive = Join-Path $ProjectRoot 'build/xray-windows.zip'
New-Item -ItemType Directory -Force (Split-Path $Archive) | Out-Null
Invoke-WebRequest "$($Lock.source)/$($Lock.version)/$($Asset.file)" -OutFile $Archive
if ((Get-FileHash $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Asset.sha256) { throw 'Xray archive checksum mismatch' }
Expand-Archive $Archive $Destination -Force
if ((Get-FileHash (Join-Path $Destination 'xray.exe') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Asset.binarySha256) { throw 'Xray binary checksum mismatch' }
