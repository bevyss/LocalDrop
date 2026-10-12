# Contributing

Thank you for helping build LocalDrop. Open an issue before large protocol changes. Keep pull requests focused and document behavior that third-party clients need to implement.

Build and test both platforms when possible. At minimum run the Windows core tests, Windows app build, Android unit tests, and Android debug build. For discovery changes, perform the physical two-device checklist in `docs/DEVELOPMENT.md` and describe the devices and network used.

Do not add transfer features without the security gates in `docs/SECURITY_ARCHITECTURE.md`. Never commit private keys, device identity files, SDK installations, or build outputs. Code is MIT licensed by contribution.
