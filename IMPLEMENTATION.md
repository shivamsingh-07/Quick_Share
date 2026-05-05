# Implementation Guide

This document describes how Quick Share for Android TV is structured and how the protocol flow is implemented in this repository.

---

## 🧱 Architecture Overview

Quick Share is layered for readability and maintenance:

### 1) Discovery Layer (BLE + mDNS)

- BLE components advertise/listen for Quick Share-related wake signals.
- mDNS advertises receiver endpoint and discovers sender/receiver peers on LAN.
- Service metadata and endpoint identity are encoded/decoded through codec utilities.

### 2) Connection Layer (TCP)

- TCP server is used in Receive mode.
- TCP client is used in Send mode.
- Frames are exchanged with length-prefixed transport framing before/after secure channel setup.

### 3) Encryption Layer (UKEY2, AES, HMAC)

- UKEY2 handshake establishes shared session secrets.
- Session keys are derived via HKDF chain.
- Secure channel wraps messages with authenticated encryption primitives:
  - AES-CBC for payload confidentiality
  - HMAC-SHA256 for integrity/authentication

### 4) Payload Layer

- Nearby/Quick Share frame types are encoded/decoded into protocol structures.
- File metadata is exchanged first (introduction/response).
- File bytes are streamed in chunks and assembled/written safely on receiver side.

---

## 🔄 Working Flow

### Receive Mode

1. Enter Receive screen.
2. Start BLE listener + mDNS advertisement.
3. Bind TCP server on ephemeral port.
4. Accept inbound socket.
5. Execute UKEY2 handshake.
6. Exchange protocol intro/response frames.
7. Prompt user for accept/reject.
8. Receive payload chunks and write files.
9. Emit completion/failure state and tear down session.

### Send Mode

1. User selects files via SAF picker.
2. App prepares send session context.
3. Discovery starts:
   - BLE-assisted path (when available)
   - mDNS peer matching
   - QR fallback if needed
4. Connect to peer TCP endpoint.
5. Execute UKEY2 handshake.
6. Send introduction metadata.
7. Wait for receiver acceptance.
8. Stream file chunks until transfer completion.
9. Publish result state and cleanup.

---

## 🔐 Protocol Details

### UKEY2 Handshake

- Sender acts as UKEY2 client; receiver acts as server.
- Both sides exchange init/finish messages and derive shared secrets.
- Auth string/PIN-compatible verification signal is derived for human confirmation flow.

### Key Derivation

- ECDH shared material feeds HKDF-based derivation chain.
- Distinct directional keys are produced for client/server traffic.
- Separate keys are used for encryption and signing contexts.

### Secure Message Structure

Each secured message contains:

- Header (IV + metadata)
- Encrypted body (device-to-device frame)
- HMAC signature over protected envelope data

Sequence validation is used to detect replay/order violations in each direction.

### Payload Transfer Mechanism

- Metadata/control messages are sent as framed protocol payloads.
- Files are chunked and transmitted with payload identifiers and offsets.
- Receiver assembles/writes chunks and finalizes file outputs through scoped storage APIs.

---

## 📡 Discovery Mechanisms

### BLE Role

- Helps with near-device signal path / discovery assistance where supported.
- Can improve discovery responsiveness on compatible devices.

### mDNS Role

- Core LAN discovery channel.
- Advertises service endpoint and discovers peers on same network segment.
- Used to resolve host+port for TCP connect.

### QR Fallback

- Used when Bluetooth path is unavailable/unreliable.
- Encodes sender context for deterministic peer matching.
- Maintains transfer usability on constrained TV environments.

---

## ⚠️ Limitations

- Devices generally need to be on the same local network for mDNS/TCP flow.
- BLE capability and reliability vary significantly across Android TV hardware.
- Background execution restrictions may interrupt idle discovery/session setup.
- Parts of Quick Share protocol behavior are inferred from interoperability references and may vary across vendor implementations.

---

## 🧪 Debugging Tips

- Use `adb logcat` and filter relevant tags from protocol/discovery/session layers.
- Verify mDNS advertisement/discovery on both peers (service visibility + address resolution).
- Validate encryption/handshake failures by checking:
  - key-derivation parity
  - sequence-number expectations
  - HMAC verification failures
- Reproduce with small files first, then large multi-file batches to isolate payload-edge issues.
- Test both directions (TV Receive and TV Send) when validating protocol changes.

---

## Notes for Contributors

- Keep UI-layer code in `ui/*`, business orchestration in `data/*`, protocol/crypto in dedicated packages.
- Avoid changing wire behavior unless backed by interoperability validation.
- Prefer incremental refactors with compile + test checks after each step.
- Preserve protocol constants and frame ordering semantics unless intentionally updating compatibility behavior.
