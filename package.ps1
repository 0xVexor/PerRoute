param([switch]$OfficialRepositories)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildRoot = Join-Path $ProjectRoot 'build'
$TaskTemp = Join-Path $BuildRoot 'tmp'
$GradleHome = Join-Path $ProjectRoot '.gradle-home'
$BootstrapGradle = Join-Path $BuildRoot 'gradle-bootstrap\gradle-9.7.0\bin\gradle.bat'
$Wrapper = Join-Path $ProjectRoot 'gradlew.bat'
$Gradle = if (Test-Path -LiteralPath $BootstrapGradle) { $BootstrapGradle } else { $Wrapper }
$Version = ((Get-Content -LiteralPath (Join-Path $ProjectRoot 'gradle.properties') | Where-Object { $_ -match '^appVersion=' }) -replace '^appVersion=', '').Trim()
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid shared appVersion in gradle.properties.' }
$GeneratedInstaller = Join-Path $BuildRoot 'generated-installer'
New-Item -ItemType Directory -Force -Path $GeneratedInstaller | Out-Null
$ReleaseInfo = Join-Path $GeneratedInstaller 'ReleaseInfo.cs'
Set-Content -LiteralPath $ReleaseInfo -Value "internal static class ReleaseInfo { internal const string Version = `"$Version`"; }" -Encoding utf8
foreach ($Manifest in 'installer', 'uninstaller', 'recovery') {
    $Template = Get-Content -LiteralPath (Join-Path $ProjectRoot "installer/$Manifest.manifest") -Raw
    Set-Content -LiteralPath (Join-Path $GeneratedInstaller "$Manifest.manifest") -Value $Template.Replace('@VERSION@', $Version) -Encoding utf8
}
$VendorDir = Join-Path $BuildRoot 'vendor'

function Get-VerifiedAsset {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [Parameter(Mandatory = $true)][long]$Length,
        [string[]]$Candidates = @()
    )

    New-Item -ItemType Directory -Force -Path $VendorDir | Out-Null
    $Destination = Join-Path $VendorDir $Name
    $Sources = @($Destination) + $Candidates
    foreach ($Source in $Sources) {
        if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) { continue }
        $File = Get-Item -LiteralPath $Source
        $Hash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
        if ($File.Length -eq $Length -and $Hash -eq $Sha256) {
            if ($Source -ne $Destination) { Copy-Item -LiteralPath $Source -Destination $Destination -Force }
            return $Destination
        }
        if ($Source -eq $Destination) { Remove-Item -LiteralPath $Destination -Force }
    }

    Write-Host "Downloading pinned installer dependency: $Name" -ForegroundColor Cyan
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    $Downloaded = Get-Item -LiteralPath $Destination
    $DownloadedHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if ($Downloaded.Length -ne $Length -or $DownloadedHash -ne $Sha256) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Downloaded dependency failed integrity verification: $Name"
    }
    return $Destination
}

New-Item -ItemType Directory -Force -Path $TaskTemp | Out-Null
$env:TEMP = $TaskTemp
$env:TMP = $TaskTemp
$env:GRADLE_OPTS = '-Dkotlin.compiler.execution.strategy=in-process'

$Jdk21 = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -Like 'jdk-21*' |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -ne $Jdk21) {
    $env:JAVA_HOME = $Jdk21.FullName
    $env:Path = (Join-Path $Jdk21.FullName 'bin') + ';' + $env:Path
}

if (-not (Test-Path -LiteralPath $Gradle)) {
    throw 'Gradle wrapper was not found. Run gradlew.bat once or restore the wrapper files.'
}

$ProxiFyreSetup = Get-VerifiedAsset `
    -Name 'ProxiFyre-2.6.2-win-x64-setup.exe' `
    -Url 'https://github.com/wiresock/proxifyre/releases/download/v2.6.2/ProxiFyre-2.6.2-win-x64-setup.exe' `
    -Sha256 '952CA0F40571194E7DFCD922A92BC3BEF1D0759A90C602E8632A9AF958C13B73' `
    -Length 2886216 `
    -Candidates @((Join-Path $env:LOCALAPPDATA 'PerRoute\downloads\ProxiFyre-2.6.2-win-x64-setup.exe'))
$VisualCppRuntime = Get-VerifiedAsset `
    -Name 'VC_redist.x64.exe' `
    -Url 'https://download.visualstudio.microsoft.com/download/pr/73aabf2e-9532-4f68-99f7-3247081a619c/CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B/VC_redist.x64.exe' `
    -Sha256 'CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B' `
    -Length 25635768 `
    -Candidates @('C:\ProgramData\Package Cache\E131B93ACDE5FFC76C19EB0784183A1E94DFA9FE06111A7830EAE3AD85838955\VC_redist.x64.exe')
$WindowsPacketFilter = Get-VerifiedAsset `
    -Name 'Windows.Packet.Filter.3.6.2.1.x64.msi' `
    -Url 'https://github.com/wiresock/ndisapi/releases/download/v3.6.2/Windows.Packet.Filter.3.6.2.1.x64.msi' `
    -Sha256 '9C388C0B7F189F7FA98720BAE2CAECF7D64F30910838B80B438ECF8956B8502C' `
    -Length 819200

& (Join-Path $ProjectRoot 'scripts/fetch_windows_xray.ps1')
$RepositoryArgs = if ($OfficialRepositories) { @('-PofficialRepositories=true') } else { @() }
& $Gradle -g $GradleHome --no-daemon @RepositoryArgs createDistributable
if ($LASTEXITCODE -ne 0) { throw 'Compose Desktop build failed.' }

# Browser extension and its runtime (helper + pinned Xray). See browser/README.md.
$BrowserRoot = Join-Path $ProjectRoot 'browser'
if ($null -eq (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required to build the browser extension.' }
if (-not (Test-Path -LiteralPath (Join-Path $BrowserRoot 'extension\node_modules'))) {
    & npm --prefix (Join-Path $BrowserRoot 'extension') ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Installing the extension build tools failed.' }
}
& node (Join-Path $BrowserRoot 'scripts\fetch-xray.mjs') windows-x64
if ($LASTEXITCODE -ne 0) { throw 'The pinned Xray release could not be fetched or verified.' }
& node (Join-Path $BrowserRoot 'scripts\cargo.mjs') build --release
if ($LASTEXITCODE -ne 0) { throw 'Building the browser runtime failed.' }
& npm --prefix (Join-Path $BrowserRoot 'extension') run build:release
if ($LASTEXITCODE -ne 0) { throw 'Building the browser extension failed.' }
& node (Join-Path $BrowserRoot 'scripts\third-party.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Collecting the browser license notices failed.' }

$HelperTarget = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $BrowserRoot 'helper\target' }
if (-not $env:CARGO_TARGET_DIR -and $HelperTarget.Length -gt 100) { $HelperTarget = Join-Path $env:USERPROFILE '.perroute-target' }
$HelperExe = @('release', 'x86_64-pc-windows-gnullvm\release', 'x86_64-pc-windows-msvc\release') |
    ForEach-Object { Join-Path $HelperTarget "$_\perroute-browser-host.exe" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if ($null -eq $HelperExe) { throw 'The browser runtime executable was not produced.' }

$ComposeImage = Join-Path $BuildRoot 'compose\binaries\main\app\PerRoute'
$DistDir = Join-Path $ProjectRoot 'dist'
$PackagedApp = Join-Path $DistDir "PerRoute-$Version"
$PortableZip = Join-Path $DistDir "PerRoute-Portable-$Version.zip"
$BrowserZip = Join-Path $DistDir "PerRoute-Browser-$Version.zip"
$BrowserStage = Join-Path $BuildRoot 'browser-payload'
$Installer = Join-Path $DistDir "PerRoute-Setup-$Version.exe"
$Csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'

if (-not (Test-Path -LiteralPath (Join-Path $ComposeImage 'PerRoute.exe'))) {
    throw 'Compose Desktop app image was not produced.'
}
if (-not (Test-Path -LiteralPath $Csc)) {
    throw 'The Windows .NET Framework C# compiler was not found.'
}

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
if (Test-Path -LiteralPath $PackagedApp) { Remove-Item -LiteralPath $PackagedApp -Recurse -Force }
if (Test-Path -LiteralPath $PortableZip) { Remove-Item -LiteralPath $PortableZip -Force }
if (Test-Path -LiteralPath $Installer) { Remove-Item -LiteralPath $Installer -Force }
Copy-Item -LiteralPath $ComposeImage -Destination $PackagedApp -Recurse -Force
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'README.md') -Destination (Join-Path $PackagedApp 'README.md') -Force
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $PackagedApp 'THIRD_PARTY_NOTICES.md') -Force
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'LICENSE') -Destination (Join-Path $PackagedApp 'LICENSE') -Force

$CommonReferences = @(
    '/reference:System.dll',
    '/reference:System.Core.dll',
    '/reference:System.Drawing.dll',
    '/reference:System.Windows.Forms.dll'
)
& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$GeneratedInstaller\uninstaller.manifest" "/out:$PackagedApp\Uninstall.exe" $CommonReferences $ReleaseInfo (Join-Path $ProjectRoot 'installer\WindowsUninstaller.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the PerRoute uninstaller.' }

& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$GeneratedInstaller\recovery.manifest" "/out:$PackagedApp\PerRouteRecovery.exe" $CommonReferences '/reference:System.Web.Extensions.dll' $ReleaseInfo (Join-Path $ProjectRoot 'installer\WindowsRecovery.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the PerRoute recovery tool.' }
Set-Content -LiteralPath (Join-Path $PackagedApp 'perroute-version.txt') -Value $Version -NoNewline -Encoding ascii

Compress-Archive -LiteralPath $PackagedApp -DestinationPath $PortableZip -CompressionLevel Optimal

# Browser runtime package: helper, pinned Xray, the unpacked extension, notices and the uninstaller.
if (Test-Path -LiteralPath $BrowserStage) { Remove-Item -LiteralPath $BrowserStage -Recurse -Force }
if (Test-Path -LiteralPath $BrowserZip) { Remove-Item -LiteralPath $BrowserZip -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $BrowserStage 'xray') | Out-Null
Copy-Item -LiteralPath $HelperExe -Destination (Join-Path $BrowserStage 'perroute-browser-host.exe')
Copy-Item -LiteralPath (Join-Path $BrowserRoot 'helper\xray\dist\windows-x64\xray.exe') -Destination (Join-Path $BrowserStage 'xray\xray.exe')
Copy-Item -LiteralPath (Join-Path $BrowserRoot 'helper\xray\dist\windows-x64\LICENSE') -Destination (Join-Path $BrowserStage 'xray\LICENSE')
Copy-Item -LiteralPath (Join-Path $BrowserRoot 'extension\dist') -Destination (Join-Path $BrowserStage 'extension') -Recurse
Copy-Item -LiteralPath (Join-Path $BrowserRoot 'licenses') -Destination (Join-Path $BrowserStage 'licenses') -Recurse
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'LICENSE') -Destination (Join-Path $BrowserStage 'LICENSE')
Copy-Item -LiteralPath (Join-Path $PackagedApp 'Uninstall.exe') -Destination (Join-Path $BrowserStage 'Uninstall.exe')
# Entries are written one by one so the archive uses standard '/' separators.
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$Archive = [System.IO.Compression.ZipFile]::Open($BrowserZip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $StageRoot = (Resolve-Path -LiteralPath $BrowserStage).Path.TrimEnd('\') + '\'
    foreach ($File in Get-ChildItem -LiteralPath $BrowserStage -Recurse -File) {
        $Entry = $File.FullName.Substring($StageRoot.Length).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $File.FullName, $Entry, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $Archive.Dispose()
}

$InstallerReferences = $CommonReferences + @(
    '/reference:System.IO.Compression.dll',
    '/reference:System.IO.Compression.FileSystem.dll'
)
$ExtensionId = Join-Path $BrowserRoot 'protocol\extension-id.txt'
& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$GeneratedInstaller\installer.manifest" "/resource:$PortableZip,PerRoute.Payload" "/resource:$BrowserZip,PerRoute.BrowserPayload" "/resource:$ExtensionId,PerRoute.ExtensionId" "/resource:$ProxiFyreSetup,PerRoute.ProxiFyreSetup" "/resource:$VisualCppRuntime,PerRoute.VisualCppRuntime" "/resource:$WindowsPacketFilter,PerRoute.WindowsPacketFilter" "/out:$Installer" $InstallerReferences $ReleaseInfo (Join-Path $ProjectRoot 'installer\WindowsInstaller.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the standalone PerRoute installer.' }

Write-Host "Compose application: $PackagedApp\PerRoute.exe" -ForegroundColor Green
Write-Host "Standalone installer: $Installer" -ForegroundColor Green
Write-Host "Portable archive: $PortableZip" -ForegroundColor Green
Write-Host "Browser runtime package: $BrowserZip" -ForegroundColor Green
