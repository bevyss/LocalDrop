param(
    [string]$Version = '0.1.0',
    [string]$OutputDirectory = '.tools/release',
    [string]$InnoCompiler = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$publish = Join-Path $root '.tools/installer-publish'
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Version must be in major.minor.patch form, for example 0.1.0.'
}

if (-not $InnoCompiler) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs/Inno Setup 6/ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6/ISCC.exe')
    )
    $InnoCompiler = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $InnoCompiler -or -not (Test-Path -LiteralPath $InnoCompiler)) {
    throw 'Inno Setup 6 ISCC.exe was not found. Install JRSoftware.InnoSetup with winget or pass -InnoCompiler.'
}

New-Item -ItemType Directory -Force -Path $publish, $output | Out-Null
& dotnet publish (Join-Path $root 'apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj') `
    -c Release -p:Platform=x64 -r win-x64 --self-contained true `
    -p:WindowsPackageType=None -p:WindowsAppSDKSelfContained=true `
    -p:PublishSingleFile=true -p:IncludeAllContentForSelfExtract=true `
    -p:PublishProfile= -o $publish
if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }

$sourceDefine = '/DSourceDir=' + $publish
$outputDefine = '/DOutputDir=' + $output
$versionDefine = '/DAppVersion=' + $Version
& $InnoCompiler $versionDefine $sourceDefine $outputDefine (Join-Path $root 'installer/LocalDrop.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup compilation failed.' }

$installer = Join-Path $output "LocalDrop-Setup-$Version-win-x64.exe"
if (-not (Test-Path -LiteralPath $installer)) { throw "Installer not found: $installer" }
Get-FileHash -LiteralPath $installer -Algorithm SHA256 | Format-List Path,Hash
