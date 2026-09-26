# Publishing and donations

Checked on 26 September 2026. Rules change: re-check the linked pages before each step. This is not legal or tax advice.

## 1. Donation link

**In use: [buymeacoffee.com/rimaturus](https://buymeacoffee.com/rimaturus)**, set in `build.sh` (`DONATE_URL`) and `.github/FUNDING.yml`. Keep it to one-off "coffees": no memberships, shop or extras, because perks turn donations into sales (see taxes below).

Cheaper alternative if fees matter later: Ko-fi with one-off tips only, paid out through Stripe (0% platform fee). Change `DONATE_URL` and `FUNDING.yml` and rebuild.

| Platform | Fees on a €5 donation from an EU card | You receive | Notes |
|---|---|---|---|
| **Ko-fi** (tips only, Stripe) | 0% Ko-fi + Stripe 1.5% + €0.25 | ≈ €4.67 | 0% only with "Contributor" status off; don't use Ko-fi shop or memberships |
| GitHub Sponsors | 0% | €5.00 | Donors need a GitHub account; W-8BEN tax form. Good as a second option on the repo page |
| Liberapay | 0% + Stripe/PayPal fees | ≈ €4.67 | Recurring donations only |
| PayPal.me (goods & services) | 3.40% + €0.35 | ≈ €4.48 | Don't use "Friends and family" for strangers' donations: PayPal can charge fees retroactively or limit the account |
| Buy Me a Coffee | 5% + Stripe | ≈ €4.30 | Most expensive |

Sources: [Ko-fi fees](https://help.ko-fi.com/hc/en-us/articles/360002506494-Does-Ko-fi-take-a-fee), [Stripe Italy pricing](https://stripe.com/it/pricing), [GitHub Sponsors fees](https://docs.github.com/en/sponsors/sponsoring-open-source-contributors/about-sponsorships-fees-and-taxes), [Liberapay FAQ](https://en.liberapay.com/about/faq), [PayPal consumer fees](https://www.paypal.com/it/digital-wallet/paypal-consumer-fees), [PayPal.Me terms](https://www.paypal.com/paypalme/pages/terms?locale.x=it_IT&country.x=IT).

### Ko-fi setup, if you switch (about 10 minutes)

1. Sign up at ko-fi.com and pick a page name, e.g. `ko-fi.com/photovault` or your name.
2. *Settings > Payments*: connect **Stripe** as an individual. If Stripe asks for a partita IVA or a company, connect **PayPal** instead (fees become 3.40% + €0.35).
3. Make sure **Contributor status is off** (0% platform fee on tips).
4. Page text: "PhotoVault is free, open source, with no ads and no tracking. Tips are voluntary and unlock nothing."
5. Don't enable shop, commissions, memberships or perks.
6. Put the link in the app and the repo:
   - app: `DONATE_URL=https://ko-fi.com/yourname ./build.sh`
   - repo: `ko_fi: yourname` in `.github/FUNDING.yml` (shows the **Sponsor** button).
7. Optional: enable GitHub Sponsors too (0%) and add `github: rimaturus` to `FUNDING.yml`.

### Taxes in Italy (keep it simple, keep records)

- **Voluntary donations with nothing in return** are most defensibly *liberalità*: not a TUIR income category, so not IRPEF income. There is no official ruling on open-source donations and commercialisti disagree, so get one paid opinion if amounts become significant. [LaLeggePerTutti](https://www.laleggepertutti.it/701818_i-proventi-da-donazioni-online-vanno-dichiarati), [FinanzaOnline thread](https://forum.finanzaonline.com/threads/donazioni-provenienti-da-applicazione-open-source.2091488/)
- **Perks turn donations into sales.** Rewards, tiers, paid features or priority support make them consideration: taxable income, and with regularity a partita IVA. Agenzia delle Entrate Risposta 137/2018 treated reward crowdfunding this way. [Il Sole 24 Ore](https://ntplusfisco.ilsole24ore.com/art/obbligo-partita-iva-volta-ottenuto-finanziamento-tramite-crowdfunding-AErOxy5G) PhotoVault's "donations unlock nothing" rule is also the tax-friendly one.
- **Donation tax** (8% between unrelated people): since 2025, small gifts of modest value are expressly excluded, and informal gifts are assessed only in specific cases. Normal €5 to €50 tips are fine. [art. 56-bis TUS](https://www.brocardi.it/testo-unico-successioni-donazioni/titolo-iii/art56bis.html), [Federnotizie](https://www.federnotizie.it/le-modifiche-del-d-lgs-139-2024-novita-per-laccertamento-e-la-tassazione-delle-liberalita-indirette/)
- **Foreign balances** (Stripe, PayPal): withdraw to your Italian bank regularly. Above €15,000 peak balance you must fill in quadro RW; above €5,000 average, IVAFE is due. [Fiscomania](https://fiscomania.com/quadro-rw-conti-esteri-5000-euro/)
- **Keep:** yearly platform statements, a screenshot of the "unlock nothing" text, receipts of project costs (e.g. the Play fee).

## 2. GitHub releases

1. Create your **release signing key** on your own PC. Never commit it, and back it up offline: losing it means users can't update.
   `keytool -genkeypair -keystore release.jks -alias photovault -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PhotoVault"`
2. Build: `KEYSTORE=release.jks KS_PASS='...' ./build.sh`
3. Make the repo public. Then create a release, attach `PhotoVault.apk`, and paste the SHA-256 lines of `apksigner verify --print-certs PhotoVault.apk` and `sha256sum PhotoVault.apk` in the notes.
4. Settings > Security: enable *Private vulnerability reporting*.
5. Optional, more reach, donation links allowed: ask **IzzyOnDroid** to list the app. It takes the APK from GitHub releases. [IzzyOnDroid policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)

Switching from the test key to the release key means uninstalling the test build once; *Sync from Amazon* restores the vault.

**Coming in 2027: Android developer verification.** Apps installed outside Google Play will need a verified developer. It starts 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and goes global in 2027. Register the package name `app.photovault` and your release key in the Android Developer Console before then. [Android developer verification](https://developer.android.com/developer-verification)

## 3. Google Play

### What Play requires

1. **Developer account.** Personal account, $25 once, government ID, and a check that you own an Android phone (Play Console app). [Play Console Help](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
2. **Closed test.** At least **12 testers** opted in for **14 days in a row**, then apply for production access. Friends and colleagues are fine; they need an Android phone and a Google account. [Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
3. **Target API 36:** done in v1.2. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
4. **Android App Bundle (.aab)**, not an APK. `build.sh` makes APKs only; the .aab needs Google's `aapt2` and `bundletool`. The simplest route is a GitHub Actions job (GitHub's servers have the Android tools) that builds and signs the .aab. [App bundles](https://android-developers.googleblog.com/2021/06/the-future-of-android-app-bundles-is.html)
5. **Play App Signing.** Upload with an *upload key*; Google holds the signing key. If Play users and GitHub users should be able to update each other's installs, give Google your own release key before the first release. [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)
6. **App content forms** (Play Console > Policy > App content):
   - Privacy policy URL: link `PRIVACY.md` on GitHub, or a GitHub Pages copy.
   - Data safety: *no data collected, no data shared*. Say that user files are sent, encrypted, to the user's own Amazon account at the user's request.
   - Ads: no. Content rating questionnaire. Target audience: 18+.
   - **Foreground service (dataSync):** description ("uploads and downloads of the user's encrypted photos that the user started"), what happens if interrupted, and a **video** of an upload continuing in the background. Use case *Network transfer: upload or download*. [Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
   - **App access:** reviewers need a login. Create a separate **test Amazon account** with Prime or the free 5 GB, a test vault password, and step-by-step instructions. [App access](https://support.google.com/googleplay/android-developer/answer/9859455)
7. **Store listing:** title without "Amazon" (e.g. *PhotoVault: encrypted photo backup*), description saying "works with Amazon Photos, not affiliated with Amazon", icon, feature graphic, 2+ screenshots. To take screenshots, use *Info > Allow screenshots*. [Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374?hl=en)

### Donations on Play

- **Links to pay the developer outside Play are not allowed**, in the app or in the store listing. This includes a link to a page that has donation links. Only validated tax-exempt organisations are exempt. [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
- Enforced: WireGuard (2019), StreetComplete (2022, for a link to its home page), AnkiDroid (September 2026). [StreetComplete #3768](https://github.com/streetcomplete/StreetComplete/issues/3768), [AnkiDroid #21656](https://github.com/ankidroid/Anki-Android/issues/21656)
- The EEA "external offers" program needs a registered business and charges fees, so it doesn't fit. [External offers (EEA)](https://support.google.com/googleplay/android-developer/answer/14372887?hl=en)
- **So the Play build has no donation button and no source link:** `PLAY=1 ./build.sh`. People who want to donate find the link on GitHub.
- **The only compliant in-app option** is Google Play Billing "tips" (consumable in-app products). Google keeps about 15%. Tips are sales, so they are taxable income and point towards a partita IVA. It also needs Google's billing library, the first third-party code in the app. Not recommended for now. [Play service fees](https://support.google.com/googleplay/android-developer/answer/16954621?hl=en)

### Real risk

Play bans apps that use a service or API against that service's terms. [Play policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en) PhotoVault uses Amazon Photos' private web API, and old Amazon Drive terms limited use to Amazon's own features. A complaint from Amazon or a strict reviewer could get the app rejected or removed. GitHub and IzzyOnDroid don't carry this risk.

### Order of work

1. Donation link live (section 1). Release key created (section 2).
2. Repo public, GitHub release, and IzzyOnDroid, where donations are fine.
3. Play: account, then .aab build, then closed test with 12 testers for 14 days, then forms and listing, then production.
