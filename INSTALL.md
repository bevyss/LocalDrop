# Install LocalDrop

LocalDrop 0.1.0 is a development build. For Windows 10/11 x64, [download the Windows installer EXE](https://github.com/bevyss/LocalDrop/releases/download/v0.1.0-preview.1/LocalDrop-Setup-0.1.0-win-x64.exe) and run it. It installs for the current user without administrator rights, adds a Start menu shortcut, and can be removed through Windows **Installed apps**. This **preview is unsigned**; Windows may display a security warning. The [release page](https://github.com/bevyss/LocalDrop/releases/tag/v0.1.0-preview.1) lists its SHA-256 hash and test summary. No signed stable Windows release or Android release APK has been published. GitHub's **Source code** ZIP is not an installer.

## Requirements

- Windows 10/11 and Android 8.0 or newer
- Both devices on the same local network, with the app open during discovery and transfer
- For building: [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0), [WinUI development prerequisites](https://learn.microsoft.com/windows/apps/get-started/start-here), JDK 17, and Android SDK Platform 36

## Build and run from GitHub source

Download the repository ZIP from [GitHub](https://github.com/bevyss/LocalDrop) using **Code → Download ZIP** and extract it, or clone the repository. Open PowerShell in the extracted repository folder.

### Windows

```powershell
dotnet build apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj -p:Platform=x64
dotnet run --project apps/windows/LocalDrop.Windows/LocalDrop.Windows.csproj -p:Platform=x64
```

This launches a development package using the WinUI tooling. It is not a production installer. If Windows Firewall asks, permit the app on **private networks** only.

To build the installer yourself, install [Inno Setup 6](https://jrsoftware.org/isdl.php) and run `./scripts/build-windows-installer.ps1` from the repository root. The output is `.tools/release/LocalDrop-Setup-0.1.0-win-x64.exe`. End users of this installer do not need the .NET SDK or WinUI development tools.

### Android

Set `ANDROID_HOME` to the Android SDK directory. From the repository root:

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is at `apps/android/app/build/outputs/apk/debug/app-debug.apk`. To install it on a device with USB debugging enabled:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses a development signing key and is for local testing. A future public release must use a stable release signing key so updates can be installed over previous releases. Android's [developer verification requirements](https://developer.android.com/developer-verification) are changing; check them before distributing a public APK.

## Use

1. Start LocalDrop on both devices and keep the Android app visible.
2. Check that the devices appear in **Nearby devices** on the same network.
3. Select the receiving device and choose a file. On first contact, compare the verification code on both screens before accepting.
4. Approve the incoming file on the receiver. Find completed files in `Downloads/LocalDrop` and the app's **Received files** view.

Guest Wi-Fi isolation, VPNs, or firewalls blocking multicast DNS can prevent discovery. See [development troubleshooting](docs/DEVELOPMENT.md#manual-lan-check).

## Verify downloads

Download files from the [Releases page](https://github.com/bevyss/LocalDrop/releases). Compare the downloaded file's hash with the release note:

```powershell
Get-FileHash .\downloaded-file -Algorithm SHA256
```

A matching hash confirms that the downloaded file matches the published release file; it does not prove that the software is harmless. See the [release security checklist](docs/RELEASE_SECURITY.md).
