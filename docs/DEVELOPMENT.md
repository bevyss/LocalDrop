# Development and validation

## Layout

- `apps/windows/LocalDrop.Core`: identity and discovery TXT validation
- `apps/windows/LocalDrop.Windows`: WinUI application and DNS-SD adapter
- `apps/android/app`: Compose application and Android NSD adapter
- `protocol/docs`: wire contract; `protocol/test-vectors`: shared examples

The two clients intentionally do not share compiled code. Interoperability depends on the documented DNS-SD contract and the same test vector.

## Manual LAN check

1. Build and launch both apps. Use a physical Android phone and Windows 10/11 PC on the same network.
2. Ensure the Windows network profile is Private and allow LocalDrop through the private firewall prompt. Do not add a public-network rule.
3. Keep Android's app open. Within about 15 seconds, confirm each app lists the other with the expected name and platform.
4. Close Android and verify Windows removes it (goodbye or at most 90 seconds). Reopen and verify it reappears.
5. Disconnect/reconnect Wi-Fi and repeat. Check that failures show status text rather than crashing.
6. If discovery fails, verify the router does not enable client isolation and UDP 5353 multicast is available. Check Windows `%LOCALAPPDATA%\LocalDrop\localdrop.log` and Android `adb logcat -s LocalDrop`.

The above is a required release gate for Phase 1. CI cannot substitute for a two-device LAN test.

### Validation record

- 2026-09-30: The project owner confirmed two-way discovery between a Windows PC and an Android phone on the same LAN. The phone disappeared from the Windows list when its app closed and reappeared when reopened. Device models and OS versions were not recorded.
- Wi-Fi disconnect and network-change cases in step 5 remain unverified on physical devices.

## Phase 2 transfer gate (in progress)

Install the new Android debug APK; the earlier Phase 1 APK cannot transfer files. Restart the Windows app from the rebuilt project so its DNS-SD record advertises `caps=file1`. For each direction, select a small harmless file, compare the 12-digit code on both screens, approve both verification prompts, then approve the receiver's file request. Confirm byte-for-byte equality of the received file. Repeat with a receiver decline, a duplicate filename, a large file, cancellation, and a disconnected network. Verify no file is published after a decline or failed transfer. Run these checks before treating Phase 2 as stable.

Validation record, 2026-09-30: the project owner completed an accepted small-file transfer from Windows to a Samsung Galaxy S23 Ultra and another from Android to Windows. Both applications logged matching transfer IDs and completion for each direction. A subsequent Windows-to-Android request was declined on the phone; the Windows sender showed “Receiver declined the transfer” and neither side logged a transfer start. During Windows-to-Android 1 GiB transfer attempts, cancellation on either device stopped the stream and left both apps running. Windows logged `transfer_cancelled` for local cancellation. ADB confirmed partial files were absent from public Downloads/LocalDrop and the Android process remained alive. Windows currently reports an Android-initiated remote socket close as a connection loss; LD/1 does not yet distinguish remote cancellation from a network failure. A 1 GiB attempt stopped after the phone screen or Wi-Fi went off; ADB disconnected at the same time. A retry with the screen and Wi-Fi kept on completed on both devices. Windows logged `transfer_completed` for the 1,073,741,824-byte file; the receiver sent success only after its SHA-256 check. ADB remained disconnected, so an independent hash of the phone file was not obtained. A repeated Windows-to-Android PNG transfer completed, and the owner confirmed both copies remained in Downloads/LocalDrop. A repeated Android-to-Windows MP4 transfer saved a second file with a `(1)` suffix; SHA-256 of both Windows copies matched. In a controlled Windows-to-Android 1 GiB attempt, switching phone Wi-Fi off after transfer start caused a Windows socket error after about 23 seconds; the Windows app remained open and reported an error. The network-error message was then clarified and rebuilt; this last wording change has not been separately exercised. For a deliberate checksum mismatch, the Windows sender was temporarily changed to advertise an all-zero SHA-256 for a 116-byte test file. Android reported a checksum error and did not publish the file. The Windows sender code was restored, rebuilt, and relaunched after the test.

Android 10+ writes received files through MediaStore into Downloads/LocalDrop without broad storage permission. Android 8–9 uses app-specific external Downloads storage because public Downloads writes would require broad storage access.

## Interface and settings validation (in progress)

The Windows and Android interfaces now have Nearby, Received files, and Settings sections. Both apps persist a user-edited device name separately from their random device ID. The Windows verification and incoming-file decisions use an in-app overlay; Android uses a branded Compose dialog. The operating-system file picker remains in use for choosing a source file.

Check on physical devices after installing the current builds:

1. Open each app. Confirm its logo, nearby devices, received-file list, and Settings section render without a crash.
2. Change the Windows device name. Confirm Android shows the new name and the device ID remains unchanged. Repeat in the opposite direction.
3. Send a small file each way. Confirm the custom verification and incoming-file prompts work, then open the received file from the Files section.
4. Cancel a transfer from each app and confirm both processes remain open.

Earlier build validation: Windows Debug x64 built with no compiler warnings and 12 core tests passed. Android debug APK built and 8 unit tests passed. The Windows app launched and remained responsive with the new layout. The Android APK was installed through wireless ADB on a Samsung Galaxy S23 Ultra; it launched without a new crash. An ADB screenshot confirmed its updated header and nearby device list, including the Windows PC. Windows logs also showed the Android peer. The owner confirmed that Windows shows the phone, the white title bar is gone, and previous received files are visible and openable on Android. Name editing and the updated transfer dialogs still need an interactive check. The language and selected-card changes were made after those checks.

Validation record, 2026-10-12: `scripts/check.ps1` passed. Windows Debug and Release x64 built with zero warnings, 12 core tests passed in each configuration, and the packaged app launched and responded. Android debug APK built and all 8 unit tests passed; the APK contains the six-language translation asset. Microsoft Defender found no threats in the Windows development AppX directory or Android debug APK, and NuGet reported no known vulnerable packages from the configured source. No Android device was connected for an interactive launch, and the six language layouts and selected-card styling still need a visual review. These checks do not establish release safety; see [release security checklist](RELEASE_SECURITY.md).

## Conventions

Keep protocol changes documented before client changes. Validate untrusted TXT data before displaying it. Add the same vector test to both clients for contract changes. Do not introduce a transfer path without TLS verification and explicit receiver consent.
