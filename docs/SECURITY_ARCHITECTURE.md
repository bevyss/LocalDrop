# Security architecture

## Phase 2 development state

The current development tree implements mutual TLS with persistent per-device certificates, first-contact code comparison on both screens, and local certificate pinning. Windows protects its RSA certificate file with user-scoped DPAPI; Android keeps a P-256 ECDSA private key in Android Keystore. The receiver must approve the metadata request before the sender streams file bytes. Control frames are limited to 16 KiB; one connection is processed at a time. A received file is published only after exact-length transfer and SHA-256 verification. One accepted small-file transfer succeeded in each direction on a physical Windows/Android pair on 2026-09-30. Treat this as experimental until rejection, cancellation, interruption, and large-file gates pass.

## Phase 1 boundary

The Phase 1 discovery layer uses mDNS records broadcast on a local link; these are neither confidential nor authenticated. A malicious LAN host can forge names, IDs, addresses, or presence. A random stable ID prevents hardware tracking but is not proof of identity. Both apps reject malformed metadata and unsupported protocol versions. The Phase 2 TCP endpoint handles only the versioned TLS protocol.

## Threat model and planned controls

| Threat | Phase 1 response | Required before transfer |
| --- | --- | --- |
| Malicious LAN device / impersonation | Treat listings as untrusted; provisional self-signed TLS followed by code comparison and certificate pinning | Physical-device security validation, rate limiting |
| Path traversal / invalid filenames | Basename validation; fixed download root or MediaStore relative path | Cross-device malicious-input tests |
| Oversized metadata / flooding | Bounded TXT and 16 KiB frames; one active transfer | Rate limits and broader fuzzing |
| Resource exhaustion | Single active session and 64 KiB transfer buffer | Disk reservation and stress tests |
| Interrupted transfers | Partial file removed on ordinary failure | Physical disconnect tests; crash recovery and resume later |

The verification code is derived from both TLS certificate hashes and fresh nonces, never from unauthenticated DNS-SD TXT alone. Both users must compare it on first contact. File acceptance is a separate explicit receiver action. No cloud account or LocalDrop server is planned.
