# AETHER Link 2.0.0

Consolidated build — not a one-line patch.

## Included
- GPS / cellular / path integrity HUD (OpenGL cluster)
- Dual-path gate + session logging
- **Offline ORT** (`aether_omega_trust.onnx` + `aether_ark.onnx`) with parked-scene guards
- OBD discovery: paired + classic BT + multi-pass BLE
- OBD connect: **classic SPP/RFCOMM ELM** first for CarPal/TOPDON, GATT fallback
- Android Auto CarApp service scaffolding

## Limits (honest)
- Android Auto on production head units usually needs Play install / internal testing
- TOPDON must be paired; close vendor app while AETHER holds the stick
- ORT can only *lower* trust vs analytic; parked + good fix cannot false-quarantine

## Build
GitHub Actions → artifact APK from `android/`

## Version
2.0.0 (versionCode 30)

## Signing
See SIGNING.md — keystores via GitHub Secrets only.

