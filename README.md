# PhotoVault v1.0

Encrypts photos/videos on the phone (AES-256-GCM), stores them in Amazon Photos as PNGs of noise.
Amazon sees only noise; the app (or `photovault.py` on a PC) decrypts them.

APK SHA-256: `048f1409d76cb2cff294dedfeefe7de34885f0abaec26e05df42885e2580bda5`
Signing certificate SHA-256: `608d39909db964de5e9df3a27da93f95033c0bd6fc08da8a6aacac2ff3c9e926`

## Files
- `src/app/photovault/Vault.java`: the whole file format and crypto (~280 lines, no Android code).
- `src/app/photovault/Amazon.java`: the Amazon Photos web calls (same endpoints as github.com/trevorhobenshield/amazon_photos).
- `src/app/photovault/MainActivity.java`: UI (setup, self-test, unlock, gallery, viewer, info).
- `photovault.py`: PC tool, same format. `dec` recovers any file without the app.
- `test/VaultTest.java`: cross-test Python <-> Java on a PC.
- `photovault.jks` (password `photovault`): signing key. Keep it private; builds signed with it install as updates.

## Format PVT2
PNG pixel bytes (8-bit RGB) = `"PVT2" | salt(16) | nonce(12) | ctLen(u64 BE) | AES-256-GCM(plain) | random padding`,
header (36 B) authenticated as GCM AAD. `plain = metaLen(u16 BE) | meta JSON {name,taken,mime} | original file`.
Key = PBKDF2-HMAC-SHA256(UTF-8 password, salt, 600000 iterations, 32 bytes).

## Build (Ubuntu 24.04, no Gradle)
    sudo apt install aapt apksigner dalvik-exchange zipalign openjdk-21-jdk-headless zip
    ./build.sh

## Cross-test
    javac -d /tmp/t src/app/photovault/Vault.java test/VaultTest.java
    PV_PASSWORD=secret python photovault.py enc photo.jpg -o enc
    java -cp /tmp/t VaultTest secret enc/<file>.png photo.jpg java.png
    PV_PASSWORD=secret python photovault.py dec java.png -o out
