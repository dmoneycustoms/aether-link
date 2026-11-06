# AETHER signing — keystores OFF GitHub

## GitHub repo secrets (Settings → Secrets and variables → Actions)
1. `AETHER_KEYSTORE_BASE64` — `base64 -w0 aether.keystore`
2. `AETHER_STORE_PASSWORD`
3. `AETHER_KEY_PASSWORD`
4. `AETHER_KEY_ALIAS` (usually `aether`)

CI restores the keystore only during the build, then deletes it.

## Never commit
`*.keystore`, `*.jks`, passwords.
