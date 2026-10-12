$ErrorActionPreference = 'Stop'
dotnet build apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj -p:Platform=x64
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
dotnet test apps/windows/LocalDrop.Core.Tests/LocalDrop.Core.Tests.csproj
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Push-Location apps/android
try {
    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon
    exit $LASTEXITCODE
} finally { Pop-Location }
