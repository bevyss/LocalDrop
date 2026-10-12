# LD/1 single-file transfer extension (Phase 2)

This extension is advertised by `caps=file1`. Discovery-only clients use `caps=` and must not be sent a transfer. App version remains separate from `pv=1`.

## Transport and trust

The DNS-SD SRV endpoint accepts TCP. LD/1 permits TLS 1.2 or 1.3 with locally generated, persistent X.509 certificates using RSA or ECDSA. The current Windows client uses RSA, the Android client uses an Android Keystore P-256 ECDSA key, and both negotiate TLS 1.2. TLS 1.3 interoperability has not been validated yet. The server requires a client certificate. The TLS stack initially accepts self-signed peer certificates **only to establish an encrypted provisional channel**. No file metadata or bytes may pass until peer verification finishes.

The client sends `hello`, then the server sends `hello`. Each contains `id`, `name`, `type`, `known` (whether the presented peer certificate matches a local pin), and a fresh 32-byte nonce encoded as 64 lowercase hex characters. The outbound client must match the server `id` to the selected DNS-SD peer. Both sides compute:

`SHA-256(UTF8("LD/1 SAS\0") || clientCertificateSHA256 || serverCertificateSHA256 || clientNonce || serverNonce)`

Certificate hashes and nonces are raw bytes in that order. Interpret the first 8 hash bytes as an unsigned big-endian integer, take modulo `1,000,000,000,000`, and display it as 12 digits in groups of four. If either `known` value is false, both users must compare the code and approve it locally. An approval frame (`verify`) is exchanged; the session continues only if both sides approve. Each app stores the peer ID → certificate SHA-256 mapping locally after both approvals. A pinned matching certificate on both sides skips the code prompt. Changed certificates require a new comparison. Discovery IDs and names are never treated as authentication.

## Framing

Control frames are a 4-byte unsigned big-endian JSON byte count, followed by exactly that many UTF-8 JSON bytes. Length must be 1–16,384 bytes. Unknown `kind`, missing fields, duplicate JSON keys, invalid UTF-8, and invalid field values are protocol errors. Only one transfer runs on a connection. Read, handshake, decision, and idle operations have timeouts.

| Direction | `kind` | Fields |
| --- | --- | --- |
| client → server | `hello` | `pv:1`, `id`, `name`, `type`, `nonce`, `known` |
| server → client | `hello` | same fields |
| both directions | `verify` | `accept:boolean` |
| client → server | `request` | `id` (32 hex), `name` (basename), `length` (0–2 TiB) |
| server → client | `response` | `accept:boolean`, `reason` (short machine-readable string if declined) |
| client → server | raw bytes | exactly `length` bytes, only after `response.accept=true` |
| client → server | `complete` | `sha256` (64 lowercase hex, digest of raw bytes) |
| server → client | `result` | `ok:boolean`, `reason` if false |

The stream is ordered; there is no Base64 or per-chunk frame. A receiver writes to a pending destination and publishes the file only after the exact length and SHA-256 match. A closed connection or cancellation deletes the partial file. A sender may close the connection to cancel. Resume and multiple files are future extensions.

Filename is a single Unicode basename: 1–255 UTF-8 bytes, no `/`, `\\`, NUL, control characters, `.` or `..`, and no trailing dot or space. The receiver must enforce its own platform restrictions and choose a unique destination name under its configured download location. A peer may never supply a path. Metadata validation and a maximum of one active inbound request bound resource use.
