$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildRoot = Join-Path $ProjectRoot 'build'
$TaskTemp = Join-Path $BuildRoot 'tmp'
$GradleHome = Join-Path $ProjectRoot '.gradle-home'
$BootstrapGradle = Join-Path $BuildRoot 'gradle-bootstrap\gradle-9.7.0\bin\gradle.bat'
$Wrapper = Join-Path $ProjectRoot 'gradlew.bat'
$Gradle = if (Test-Path -LiteralPath $BootstrapGradle) { $BootstrapGradle } else { $Wrapper }
$Version = '3.5.1'
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

& $Gradle -g $GradleHome --no-daemon createDistributable
if ($LASTEXITCODE -ne 0) { throw 'Compose Desktop build failed.' }

$ComposeImage = Join-Path $BuildRoot 'compose\binaries\main\app\PerRoute'
$DistDir = Join-Path $ProjectRoot 'dist'
$PackagedApp = Join-Path $DistDir "PerRoute-$Version"
$PortableZip = Join-Path $DistDir "PerRoute-Portable-$Version.zip"
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
& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$ProjectRoot\installer\uninstaller.manifest" "/out:$PackagedApp\Uninstall.exe" $CommonReferences (Join-Path $ProjectRoot 'installer\WindowsUninstaller.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the PerRoute uninstaller.' }

# The standalone recovery tool: puts the network back and rolls PerRoute back even when PerRoute does not open.
& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$ProjectRoot\installer\recovery.manifest" "/out:$PackagedApp\PerRouteRecovery.exe" $CommonReferences '/reference:System.Web.Extensions.dll' (Join-Path $ProjectRoot 'installer\WindowsRecovery.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the PerRoute recovery tool.' }
Set-Content -LiteralPath (Join-Path $PackagedApp 'perroute-version.txt') -Value $Version -NoNewline -Encoding ascii

Compress-Archive -LiteralPath $PackagedApp -DestinationPath $PortableZip -CompressionLevel Optimal

$InstallerReferences = $CommonReferences + @(
    '/reference:System.IO.Compression.dll',
    '/reference:System.IO.Compression.FileSystem.dll'
)
& $Csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$ProjectRoot\src\main\resources\icons\perroute.ico" "/win32manifest:$ProjectRoot\installer\installer.manifest" "/resource:$PortableZip,PerRoute.Payload" "/resource:$ProxiFyreSetup,PerRoute.ProxiFyreSetup" "/resource:$VisualCppRuntime,PerRoute.VisualCppRuntime" "/resource:$WindowsPacketFilter,PerRoute.WindowsPacketFilter" "/out:$Installer" $InstallerReferences (Join-Path $ProjectRoot 'installer\WindowsInstaller.cs')
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the standalone PerRoute installer.' }

Write-Host "Compose application: $PackagedApp\PerRoute.exe" -ForegroundColor Green
Write-Host "Standalone installer: $Installer" -ForegroundColor Green
Write-Host "Portable archive: $PortableZip" -ForegroundColor Green
