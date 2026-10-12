# Roadmap

## Phase 2: verified single-file transfer

The bounded connection protocol, cross-platform test vectors, mutual TLS, first-contact code comparison, explicit receiver accept/decline, and streamed single-file implementation are in the development tree. Small accepted files in both directions and a 1 GiB Windows-to-Android file completed on physical devices on 2026-09-30. A receiver-declined request, cancellation on both devices, duplicate names on both receivers, a controlled phone Wi-Fi loss, and Android rejection of a deliberately corrupted SHA-256 were also verified. **Remaining gate:** repeat a normal transfer after restoring the test sender and verify the final network-error wording on a physical device. Fix issues before calling Phase 2 stable.

## Later candidates

- Multiple-file and folder transfer
- Transfer resume
- Clipboard and text sharing
- QR-code pairing
- Trusted-device management and optional auto-accept
- Transfer history
- Wi-Fi Direct
- Linux, macOS, and iOS clients
- Optional compression
- LocalDrop CLI and protocol SDK

Progress and cancellation are part of the experimental Phase 2 implementation. The later candidates above are not implemented in 0.1.0.
