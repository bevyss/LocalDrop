# LocalDrop Discovery Protocol (LD/1 draft)

LD/1 is versioned independently of app version 0.1.0. This document specifies the implemented **discovery subset**. No connection, pairing, or transfer message is defined or accepted in Phase 1.

## DNS-SD

Each running app registers one `_localdrop._tcp.local.` instance through mDNS on the local link. The instance label is a display name plus a short random ID suffix. Clients must use the TXT `id`, never the instance label or IP address, as the stable discovery key. Standard DNS-SD PTR, SRV, TXT, and A/AAAA records carry the service, endpoint, properties, and interface addresses. Addresses are discovered from DNS records, not stored as identity. The SRV port is an ephemeral bound TCP port. Phase 1 closes accepted sockets without processing data; Phase 2 clients use it for TLS.

TXT keys (UTF-8 values, ASCII keys):

| Key | Required | Value |
| --- | --- | --- |
| `txtvers` | yes | `1` |
| `pv` | yes | `1` (LD/1 major version) |
| `id` | yes | 32 lowercase hexadecimal characters, generated once from 16 cryptographic random bytes |
| `name` | yes | UTF-8 display name, at most 64 UTF-8 bytes |
| `type` | yes | `windows` or `android` |
| `caps` | yes | Empty for discovery-only clients; `file1` for the single-file transfer extension |

Unknown TXT keys are ignored. Invalid or overlong values and unsupported `pv` are ignored. IDs are public pseudonyms, **not authentication**. TXT fields and IP addresses are untrusted. Clients suppress their own ID and deduplicate peers by ID. An instance is removed on DNS-SD goodbye or after 90 seconds without a fresh announcement; implementations may query periodically to refresh the list. The UI must indicate discovery failure instead of inventing devices.

## Connection extension

Phase 2 clients advertising `caps=file1` implement [single-file transfer](LD-1-transfer.md). Discovery-only clients continue to advertise an empty `caps` value.

Future capabilities require a documented LD/1 extension or LD/2 revision with compatibility tests. A discovery record alone never implies trust.

## Interoperability vector

See `../test-vectors/discovery.json`. A third-party client can implement Phase 1 by advertising and browsing the service and TXT contract above.
