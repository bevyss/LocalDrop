# Changelog

## 0.1.0 — development

- Initial Windows and Android discovery applications
- LD/1 discovery contract and test vector
- Persistent random identity and basic logs
- Two-way Windows ↔ Android LAN discovery and phone disappearance/reappearance confirmed by the project owner on 2026-09-30
- Experimental mutual-TLS single-file transfer with verification code, receiver approval, bounded streaming, checksum, and cancellation
- Fixed Windows TLS certificate loading for Schannel so the saved certificate can be used during the handshake
- Android TLS identity now uses a P-256 ECDSA key in Android Keystore; migrated the incompatible development RSA identity
- Windows to Android single-file transfer confirmed on physical devices on 2026-09-30
- Android to Windows single-file transfer confirmed on the same physical devices on 2026-09-30
- Receiver-declined request confirmed on physical devices; cancellation dialogs now close when Cancel is pressed
- Fixed Android cancellation crash caused by closing the TLS socket on the UI thread; receiver-side cancellation of a 1 GiB transfer was verified without publishing a partial file
- Verified a complete 1 GiB Windows-to-Android transfer on physical devices
- Hardened Windows transfer timeout/cancellation to close the TCP connection as well as cancel pending tasks, with distinct user messages for local cancellation and network timeout
- Verified Android rejects a deliberately incorrect SHA-256 and leaves no received file; restored the normal Windows sender immediately afterward
- Added the supplied LocalDrop wordmark and a matching square launcher icon, custom transfer prompts, a frameless-content Windows title bar, received-file views, and persistent editable device names on both clients
- Removed an extra Windows DNS-SD announcement that caused an application crash on the current LAN; the service continues to advertise and answer discovery queries
