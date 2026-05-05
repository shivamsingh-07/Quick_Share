# Future Enhancements

Backlog of Quick Share–related capabilities that are **intentionally out of scope** for the current release. Each item keeps the same intent as before: what it is, why it is deferred, where to read upstream code, and rough effort/risk.

When you implement something here, track it in issues/milestones, link the PR, and remove or shrink the entry once it ships.

---

## 🔧 Overview

This document complements:

- **[IMPLEMENTATION.md](IMPLEMENTATION.md)** — architecture and protocol notes for this app
- **[Martichou/rquickshare](https://github.com/Martichou/rquickshare)** — Rust reference (`rqs_lib`-style) for wire behaviour
- **[grishka/NearDrop](https://github.com/grishka/NearDrop)** — protocol documentation and another mature Quick Share–compatible implementation

External links below point at **`master`** on those repos (current default branches as exposed by the GitHub API).

---

## 📡 Discovery enhancements

### BLE ↔ mDNS bootstrap (beyond diagnostics)

**What.** Today we only **log** BLE ↔ mDNS correlation. A fuller story (as in [rquickshare](https://github.com/Martichou/rquickshare) / [NearDrop](https://github.com/grishka/NearDrop) thinking) is to **tie** a FE2C advertisement to a specific mDNS `_FC9F5ED42C8A._tcp` instance so senders can pick the right peer when multicast TXT/SRV is lossy. Optionally boost or reorder picker rows when mDNS and a fresh FE2C beacon align.

**Why deferred.** Android TV BLE scanning is unreliable on many chipsets (no callbacks even when `startScan` “succeeds”). Anything that depends on scan callbacks must stay **best-effort**, not on the critical connect path.

**Reference (Rust).**

- [core_lib/src/hdl/ble.rs](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/hdl/ble.rs) — BLE handling
- [core_lib/src/hdl/blea.rs](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/hdl/blea.rs) — BLE advertiser (upstream splits advertiser vs listener concerns; there is no `ble_listener.rs` in this tree)
- [core_lib/src/hdl/mdns_discovery.rs](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/hdl/mdns_discovery.rs) — discovery plumbing

**NearDrop (protocol / behaviour context).**

- [PROTOCOL.md](https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md) — high-level Quick Share protocol notes

**Approach when tackled.** Parse stable FE2C fields where documented; correlate with service-name / endpoint id bytes from mDNS where possible. Gate any picker reordering behind a **settings toggle** (default off); always fall back to current mDNS-only behaviour.

**Effort.** Multi-day interop on real phones + several TV models.

---

### mDNS visibility modes (Visible / Invisible / temporary)

**What.** Stock Quick Share can treat visibility as more than “on/off” (e.g. temporary visibility windows).

**Why deferred.** Our TV advertises while Receive is on screen and stops when the user leaves (`DisposableEffect`). That already covers the common “guest session” ergonomics without extra UI.

---

### Persistent device identity (`endpointId`)

**What.** Random 4-byte endpoint id per session could instead be persisted so phones recognise the TV across reboots.

**Why deferred.** Mostly cosmetic; stock UI often keys off display name anyway.

**Approach.** Persist four bytes (e.g. DataStore / SharedPreferences), rotate on explicit user action.

---

### FE2C advertiser payload — field A/B testing

**What.** Measure wake-up latency and discovery reliability across Pixel, Galaxy, and common TV chipsets with different FE2C payload strategies.

**Why deferred.** Needs controlled device matrix and time; no change to defaults until data exists.

**Extra reading.** [IMPLEMENTATION.md](IMPLEMENTATION.md) (BLE / discovery overview); upstream [rquickshare](https://github.com/Martichou/rquickshare) for Rust reference.

---

## 🔐 Protocol / encryption improvements

### Mutual-PIN auto-accept (`PairedKeyResult = SUCCESS`)

**What.** Paired-key frames exist so previously verified devices can skip PIN confirmation. Today we mirror **UNABLE** because there is no contact graph or certificate store.

**Why deferred.** Needs persisted trust material, certificate lifecycle, and TOFU UX — large effort for limited TV benefit.

**Reference (protobuf).**

- [core_lib/src/proto_src/wire_format.proto](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/proto_src/wire_format.proto) — includes `CertificateInfoFrame`, `PublicCertificate`, and related messages

---

### Text / URL / Wi-Fi credential introductions

**What.** `IntroductionFrame` can carry `text_metadata[]`, URLs, and `wifi_credentials_metadata[]`. [rquickshare](https://github.com/Martichou/rquickshare) decodes these into richer UI; we currently treat non-file introductions as zero-payload completion.

**Why deferred.** Primary TV use case is media files; Wi-Fi credential payloads need careful security UX.

**Reference.**

- [core_lib/src/hdl/inbound.rs](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/hdl/inbound.rs) — see `process_introduction` and related helpers (e.g. password payload parsing)

**Pointer.** Receiver counting of expected payloads already includes non-file types in principle; work is decode + surface `ReceiveEvent` variants.

---

### Bandwidth-upgrade negotiation (Wi-Fi Direct / hotspot)

**What.** `BandwidthUpgradeNegotiationFrame` can move the encrypted session to a higher-throughput path. Stock Android Quick Share uses this heavily for large sends.

**Why deferred.** LAN is often enough for TV; protocol surface is thinly documented.

**Reference.**

- [core_lib/src/proto_src/offline_wire_formats.proto](https://github.com/Martichou/rquickshare/blob/master/core_lib/src/proto_src/offline_wire_formats.proto) — frame definitions (`rqs_lib` defines but does not fully exercise this path)

---

### Real Quick Share certificate verification

**What.** `PairedKeyEncryptionFrame` fields are stubs unless wired to real trust infrastructure.

**Why deferred.** Depends on ecosystem pieces that are not publicly reproducible in an open-source TV client.

---

### Handshake & frame metadata parity with stock Quick Share

**What.** Small wire-shape gaps called out in [IMPLEMENTATION.md](IMPLEMENTATION.md): `ConnectionRequest.mediums`, ASCII `endpoint_id` aligned with mDNS service name, `ConnectionResponse.os_info`, non-zero paired-key stubs, keep-alive behaviour, etc.

**Why deferred.** Interop works today; these reduce warnings and edge-case refusals on newer Quick Share builds rather than fixing one visible break.

---

## 📁 Transfer improvements

### Crash-resilient resume

**What.** Persist per-payload byte offsets so a dropped session could resume instead of restarting from zero.

**Why deferred.** TCP already retransmits within a session; true resume needs re-handshake + payload-id negotiation — meaningful mostly for very large transfers.

**Approach.** Persist `(payloadId, expectedSize, bytesWritten)`; on reconnect from same peer, offer resume.

---

### Configurable storage location

**What.** Let users change download folder / per-MIME routing beyond the current SAF grant.

**Why deferred.** UX scope; current SAF usage is correct and safe.

---

## ⚠️ Known gaps / TODOs

### Contributor guardrails

- **Inbound duplicate basenames.** We do not pre-compute `name (1).ext` in app code; `MediaStore` may adjust display names. Do not reopen collision UX without a real report.
- **Wire compatibility.** Proto field numbers and `EndpointInfo` layout are tuned for interop with stock Quick Share and [rquickshare](https://github.com/Martichou/rquickshare). Changes need phone-side verification in the PR.
- **Docs stay honest.** When behaviour intentionally diverges from upstream, record it in [IMPLEMENTATION.md](IMPLEMENTATION.md).

---

### Reference repos (bookmark)

| Resource    | URL                                                                          |
| ----------- | ---------------------------------------------------------------------------- |
| rquickshare | [github.com/Martichou/rquickshare](https://github.com/Martichou/rquickshare) |
| NearDrop    | [github.com/grishka/NearDrop](https://github.com/grishka/NearDrop)           |
