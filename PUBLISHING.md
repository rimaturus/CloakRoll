# Publishing checklist

## GitHub (ready)

1. Create a **new release signing key on your own PC** (never commit it, back it up offline):
   `keytool -genkeypair -keystore release.jks -alias photovault -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PhotoVault"`
2. Build: `KEYSTORE=release.jks KS_PASS='...' ./build.sh`
3. Create a release, attach `PhotoVault.apk`, and paste in the notes the output of
   `apksigner verify --print-certs PhotoVault.apk` (the SHA-256 line) and `sha256sum PhotoVault.apk`.
4. Turn on GitHub Sponsors (or change `.github/FUNDING.yml`, `DONATE_URL` in `build.sh`, and the README link).
5. Enable *Private vulnerability reporting* in Settings → Security.

A new signing key means users of test builds must uninstall once; their vault comes back with *Sync from Amazon*.

## Google Play (possible, with open questions)

Already done:
- Target API 36, required for new apps since 31 August 2026.
- `PLAY=1 ./build.sh` removes the in-app donation link. Play's payments policy doesn't allow linking to external donations for the developer; open-source apps such as WireGuard (2019) and StreetComplete (2022) were rejected for this.
- `PRIVACY.md` can be the privacy-policy URL. Data safety form: *no data collected, no data shared*; data is encrypted in transit.

Still to do:
- Play needs an **Android App Bundle (.aab)**, not an APK, signed with an upload key (Play App Signing). `build.sh` doesn't make AABs yet.
- Declare the **dataSync foreground service** in Play Console, with a short video of an upload in the background.
- Store listing: at least 2 screenshots (allow screenshots temporarily in *Info*), feature graphic, description. Don't use Amazon's logo, and describe it as "works with Amazon Photos", not as an Amazon product.

Real risk: the app drives Amazon's private web API and stores encrypted data in a photo service. Play review could reject it (policies on unauthorised access to third-party services, trademarks), and Amazon could object. GitHub releases don't have this problem.
