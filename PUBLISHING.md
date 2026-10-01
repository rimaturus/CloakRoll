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
4. Page text: "Cloakroll is free, open source, with no ads and no tracking. Tips are voluntary and unlock nothing."
5. Don't enable shop, commissions, memberships or perks.
6. Put the link in the app and the repo:
   - app: `DONATE_URL=https://ko-fi.com/yourname ./build.sh`
   - repo: `ko_fi: yourname` in `.github/FUNDING.yml` (shows the **Sponsor** button).
7. Optional: enable GitHub Sponsors too (0%) and add `github: rimaturus` to `FUNDING.yml`.

### Taxes in Italy (keep it simple, keep records)

- **Voluntary donations with nothing in return** are most defensibly *liberalità*: not a TUIR income category, so not IRPEF income. There is no official ruling on open-source donations and commercialisti disagree, so get one paid opinion if amounts become significant. [LaLeggePerTutti](https://www.laleggepertutti.it/701818_i-proventi-da-donazioni-online-vanno-dichiarati), [FinanzaOnline thread](https://forum.finanzaonline.com/threads/donazioni-provenienti-da-applicazione-open-source.2091488/)
- **Perks turn donations into sales.** Rewards, tiers, paid features or priority support make them consideration: taxable income, and with regularity a partita IVA. Agenzia delle Entrate Risposta 137/2018 treated reward crowdfunding this way. [Il Sole 24 Ore](https://ntplusfisco.ilsole24ore.com/art/obbligo-partita-iva-volta-ottenuto-finanziamento-tramite-crowdfunding-AErOxy5G) Cloakroll's "donations unlock nothing" rule is also the tax-friendly one.
- **Donation tax** (8% between unrelated people): since 2025, small gifts of modest value are expressly excluded, and informal gifts are assessed only in specific cases. Normal €5 to €50 tips are fine. [art. 56-bis TUS](https://www.brocardi.it/testo-unico-successioni-donazioni/titolo-iii/art56bis.html), [Federnotizie](https://www.federnotizie.it/le-modifiche-del-d-lgs-139-2024-novita-per-laccertamento-e-la-tassazione-delle-liberalita-indirette/)
- **Foreign balances** (Stripe, PayPal): withdraw to your Italian bank regularly. Above €15,000 peak balance you must fill in quadro RW; above €5,000 average, IVAFE is due. [Fiscomania](https://fiscomania.com/quadro-rw-conti-esteri-5000-euro/)
- **Keep:** yearly platform statements, a screenshot of the "unlock nothing" text, receipts of project costs (e.g. the Play fee).

## 2. GitHub releases

1. Create your **release signing key** on your own PC. Never commit it, and back it up offline: losing it means users can't update.
   `keytool -genkeypair -keystore release.jks -alias photovault -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cloakroll"`
2. Build: `KEYSTORE=release.jks KS_PASS='...' ./build.sh`
3. Make the repo public. Then create a release, attach `PhotoVault.apk`, and paste the SHA-256 lines of `apksigner verify --print-certs PhotoVault.apk` and `sha256sum PhotoVault.apk` in the notes.
4. Settings > Security: enable *Private vulnerability reporting*.
5. Optional, more reach, donation links allowed: ask **IzzyOnDroid** to list the app. It takes the APK from GitHub releases. [IzzyOnDroid policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)

Switching from the test key to the release key means uninstalling the test build once; *Sync* restores the vault.

**Coming in 2027: Android developer verification.** Apps installed outside Google Play will need a verified developer. It starts 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and goes global in 2027. Register the package name `io.github.rimaturus.photovault` and your release key in the Android Developer Console before then. [Android developer verification](https://developer.android.com/developer-verification)

## 3. Google Play

### What Play requires

1. **Developer account.** Personal account, $25 once, government ID, and a check that you own an Android phone (Play Console app). [Play Console Help](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
2. **Closed test.** At least **12 testers** opted in for **14 days in a row**, then apply for production access. Friends and colleagues are fine; they need an Android phone and a Google account. [Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
3. **Package name `io.github.rimaturus.photovault`** (the app uses it since v1.6). It is permanent once the app is created. It sits under your GitHub name, `rimaturus.github.io`, which you control.
   **Target API 36:** done in v1.2. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
4. **Android App Bundle (.aab)**, not an APK: `KS_PASS='...' PLAY=1 AAB=1 ./build.sh` makes `PhotoVault-play.aab` (aapt2 + bundletool, downloaded into `sdk/` on first use). It is signed with your key, which becomes the **upload key**. It also carries **code transparency**, signed with the same key. Anyone can check it: `bundletool check-transparency --mode=bundle --bundle=PhotoVault-play.aab` prints the key fingerprint, which you publish in the README. After Play delivers the app, the same check works on the installed APKs (`--mode=apk`).
5. **Play App Signing.** Upload with an *upload key*; Google holds the signing key. If Play users and GitHub users should be able to update each other's installs, give Google your own release key before the first release. [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)
6. **App content forms** (Play Console > Policy > App content):
   - Privacy policy URL: link `PRIVACY.md` on GitHub, or a GitHub Pages copy.
   - Data safety: *no data collected, no data shared*. Say that user files are sent, encrypted, to the user's own Amazon or Microsoft account at the user's request.
   - Ads: no. Content rating questionnaire. Target audience: 18+.
   - **Foreground service (dataSync):** description ("uploads and downloads of the user's encrypted photos that the user started"), what happens if interrupted, and a **video** of an upload continuing in the background. Use case *Network transfer: upload or download*. [Foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
   - **Photo and video permissions** (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, since v1.8 for automatic backup): Play requires a declaration form for broad access. Use case *backup and cloud storage*; say the permission is asked only when the user turns on automatic backup, and that the photo picker is used otherwise. [Photo and video permissions policy](https://support.google.com/googleplay/android-developer/answer/14115180)
   - **App access:** reviewers need a login. Create a separate **test Microsoft account** (free, 5 GB of OneDrive: the easiest for reviewers) and/or a **test Amazon account**, a test vault password, and step-by-step instructions. [App access](https://support.google.com/googleplay/android-developer/answer/9859455)
7. **"Create app" form:**
   - App, free (this can't be changed to paid later).
   - **Automatic protection: turn it off.** Google would rewrite the Play version to add its own licence check against copies installed outside Play. That protects against piracy, which a free GPL app doesn't need. It shows nothing to users. And the Play binary would no longer match the published source. Once on, it can only be switched off release by release. [Automatic protection](https://support.google.com/googleplay/android-developer/answer/10183279?hl=en)
   - Accept the three declarations. On US export rules: the app uses only standard cryptography (AES from Android) and is open source.
   - Instead, add **code transparency** to the .aab (`bundletool add-transparency`, with a key only you hold). Anyone can then check that the code installed from Play is the code you built. That is the trust signal that fits an open-source app. [Code transparency](https://developer.android.com/guide/app-bundle/code-transparency)
8. **Store listing:** title without "Amazon" (e.g. *Cloakroll: encrypted photo backup*), description saying "works with Amazon Photos, not affiliated with Amazon", icon, feature graphic, 2+ screenshots. To take screenshots, use *Settings > Allow screenshots*. [Impersonation policy](https://support.google.com/googleplay/android-developer/answer/9888374?hl=en)

### Donations on Play

- **Links to pay the developer outside Play are not allowed**, in the app or in the store listing. This includes a link to a page that has donation links. Only validated tax-exempt organisations are exempt. [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
- Enforced: WireGuard (2019), StreetComplete (2022, for a link to its home page), AnkiDroid (September 2026). [StreetComplete #3768](https://github.com/streetcomplete/StreetComplete/issues/3768), [AnkiDroid #21656](https://github.com/ankidroid/Anki-Android/issues/21656)
- The EEA "external offers" program needs a registered business and charges fees, so it doesn't fit. [External offers (EEA)](https://support.google.com/googleplay/android-developer/answer/14372887?hl=en)
- **So the Play build has no donation button and no source link:** `PLAY=1 ./build.sh`. People who want to donate find the link on GitHub.
- **The only compliant in-app option** is Google Play Billing "tips" (consumable in-app products). Google keeps about 15%. Tips are sales, so they are taxable income and point towards a partita IVA. It also needs Google's billing library, the first third-party code in the app. Not recommended for now. [Play service fees](https://support.google.com/googleplay/android-developer/answer/16954621?hl=en)

### Real risk

Play bans apps that use a service or API against that service's terms. [Play policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en) Cloakroll uses Amazon Photos' private web API, and old Amazon Drive terms limited use to Amazon's own features. A complaint from Amazon or a strict reviewer could get the app rejected or removed. GitHub and IzzyOnDroid don't carry this risk. OneDrive goes through Microsoft's official API, so it doesn't have this problem; if Play objects to the Amazon part, a Play build with OneDrive only is a way out.

### Order of work

1. Donation link live (section 1). Release key created (section 2). Microsoft app registered (section 4).
2. Repo public, GitHub release, and IzzyOnDroid, where donations are fine.
3. Play: account, then .aab build, then closed test with 12 testers for 14 days, then forms and listing, then production.

## 4. OneDrive app registration

Every app that signs in with a Microsoft account must be registered with Microsoft. The registration is free and gives an **Application (client) ID**, which goes into the build. There is no secret: the app signs in with OAuth 2.0 + PKCE in the browser. One registration serves all users.

### What you need

- A **Microsoft Entra directory (tenant)**. Personal Microsoft accounts can no longer register apps outside a directory. If you don't have one (work or school), the usual way is a free Azure account at [azure.microsoft.com/free](https://azure.microsoft.com/free): it creates a "Default Directory". Azure may ask for a card to verify your identity; app registrations cost nothing.

### Steps (about 10 minutes)

1. Sign in at [entra.microsoft.com](https://entra.microsoft.com), open **App registrations** (the search box at the top finds it if the menu differs) > **New registration**.
2. **Name: `PhotoVault`.** OneDrive names the app's folder after it (`Apps/PhotoVault`).
3. **Supported account types:** *Accounts in any organizational directory and personal Microsoft accounts*. (The app signs in through the `common` endpoint, which needs this option.)
4. **Redirect URI:** platform *Public client/native (mobile & desktop)*, value exactly:
   `io.github.rimaturus.photovault://auth`
   Then **Register**.
5. On the app's page, copy the **Application (client) ID**.
6. Optional but tidy: **API permissions > Add a permission > Microsoft Graph > Delegated**: `Files.ReadWrite.AppFolder` and `offline_access` (and `Files.ReadWrite`, used only if a user picks the "full OneDrive access" fallback). Don't grant admin consent; users consent for themselves.
7. Build with it:
   `ONEDRIVE_CLIENT_ID='the-client-id' KS_PASS='...' ./build.sh`
   The client ID is not a secret: it ends up inside the APK and in the sign-in URL.

### Good to know

- The consent screen shows Cloakroll as **unverified**. Publisher verification needs a Microsoft Cloud Partner Program account (a business), so a personal project stays unverified. Personal Microsoft accounts can still consent; some organisations block unverified apps for their work accounts.
- The app asks only for `Files.ReadWrite.AppFolder`: it sees its own folder and nothing else in the user's OneDrive. If Microsoft refuses the app folder for a new registration (it happens), setup offers *full OneDrive access* instead; the vault then goes in a normal folder `PhotoVault`.
- Refresh tokens of public clients last up to 90 days without use; after that the app asks to sign in again. Users can remove the app's access at [account.live.com/consent/Manage](https://account.live.com/consent/Manage).
- Why not Google Photos: the Google Photos API allows uploading only real photos and videos, has no delete, and doesn't return bit-exact originals, which encrypted files need. Google Drive works: see the next section.

## 5. Google Drive OAuth client

Google Drive needs a Google Cloud project with an OAuth client. Free; the **Client ID** goes into the build. No secret: the app signs in with OAuth 2.0 + PKCE in the browser.

Why the client type is **iOS**: for Android clients Google allows only its own sign-in libraries (Google Play services), and custom URI scheme redirects are disabled for new Android clients. The iOS client type still allows a browser sign-in whose redirect is the app's bundle id as a URI scheme (`io.github.rimaturus.photovault:/oauth2redirect`), which Android delivers to the app like the OneDrive redirect. Nothing iOS-specific is used.

### Steps (about 10 minutes)

1. At [console.cloud.google.com](https://console.cloud.google.com), create a project, e.g. `Cloakroll`.
2. **APIs & Services > Library > Google Drive API > Enable.**
3. **Google Auth Platform > Branding:** app name `Cloakroll`, user support email, developer contact email. **Audience:** *External*, then **Publish app** (while "Testing", only listed test users can sign in and their sign-in expires after 7 days).
4. **Data access > Add or remove scopes:** only `https://www.googleapis.com/auth/drive.file`. It is a non-sensitive scope: no Google verification is needed (a logo on the consent screen would need brand verification, so leave it out).
5. **Clients > Create client:** type **iOS**, name e.g. `Cloakroll (Android, browser sign-in)`, **Bundle ID** exactly `io.github.rimaturus.photovault`. Create, copy the **Client ID** (`....apps.googleusercontent.com`).
6. Build with it:
   `GOOGLE_CLIENT_ID='....apps.googleusercontent.com' KS_PASS='...' ./build.sh`

### Good to know

- `drive.file` means the app sees only the files it created (the folder `Cloakroll` and its contents), on any phone signed in to the same account, and nothing else in the user's Drive.
- Users can remove the app's access at [myaccount.google.com/connections](https://myaccount.google.com/connections).

## 6. Play compliance checklist (v1.9.2)

What each Play policy asks and how Cloakroll answers it. Re-check the linked pages before each release.

| Policy | Where it applies | Cloakroll |
|---|---|---|
| [User Data](https://support.google.com/googleplay/android-developer/answer/10144311): privacy policy linked in the listing **and** in the app | Store listing field; app | Listing: the URL below. App: *Privacy policy* on the welcome screen and in *Settings > About*, the full text of `PRIVACY.md` built into the app |
| User Data: prominent disclosure and consent before accessing personal data | Photos permission | Asked only when the user turns on automatic backup, after a dialog that says what is read and that a copy of the key stays on the phone; the photo picker otherwise |
| [Photo and Video Permissions](https://support.google.com/googleplay/android-developer/answer/14115180) | `READ_MEDIA_IMAGES/VIDEO` | Declaration form: core use case **backup and cloud storage**; permission requested only for automatic backup, picker used for one-off selection; Android 14 "selected photos" supported |
| [Foreground services](https://support.google.com/googleplay/android-developer/answer/13392821) | `dataSync` | Declaration: user-started uploads and downloads of the user's encrypted files that must finish when the app is left; attach a screen recording |
| Data safety form | Policy > App content | See the answers below |
| [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) | Apps with accounts | Not applicable: the app creates no accounts. Answer "no" to account creation |
| [Permissions](https://support.google.com/googleplay/android-developer/answer/9888170): minimum needed | Manifest | Nine permissions, each justified in the README table; no `MANAGE_EXTERNAL_STORAGE`, no location, no contacts |
| [Payments](https://support.google.com/googleplay/android-developer/answer/9858738) | Donations | Play build has no donation button or link (`PLAY=1`); nothing in the listing either |
| [Impersonation](https://support.google.com/googleplay/android-developer/answer/9888374) | Amazon/Microsoft/Google names | Title without their names; "not affiliated" in the listing and in the policy; their names used only to say what the app works with |
| [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646): no use of a service against its terms | Amazon web API | The known risk (section 3). OneDrive and Google Drive use official APIs with the narrowest scopes (`Files.ReadWrite.AppFolder`, `drive.file`) |
| [Country requirements](https://support.google.com/googleplay/android-developer/answer/6223646) | Brazil, EU, Japan, Korea, Vietnam, Israel, India | No purchases, no games, no financial features: nothing applies. EU: no geo-blocking; the app is offered in every country. Since v1.9.1 the Amazon site is chosen by the user (amazon.com, .co.uk, .de, .fr, .it, .es, .nl, .se, .pl, .com.be, .ie, .ca, .com.mx, .co.jp, .com.au), so non-EU accounts work |
| Target API level | Manifest | targetSdk 36, minSdk 33 |
| US export (encryption) | "Create app" declarations | Standard AES-GCM from Android's own libraries, no custom cryptography, source public: mass-market exemption (5D992 / ENC "publicly available"). No EAR filing needed for publicly available open-source encryption; keep a note of the GitHub URL |
| Families / age | Content rating | Not for children; target audience 18+; the policy says so |
| Code transparency | App Bundle | `bundletool add-transparency` with your key; the fingerprint is in the README |

### Data safety answers

Play defines "collected" as data transmitted off the device. Photos are transmitted, encrypted, to the user's own storage account at the user's request, and nothing reaches the developer. Answer:

- **Does your app collect or share any of the required user data types?** Yes (the encrypted photos leave the device).
- **Is all of the user data collected by your app encrypted in transit?** Yes.
- **Do you provide a way for users to request that their data is deleted?** Yes (the user deletes items or the vault folder in their own storage; explain in the policy link).
- **Photos and videos** → Collected: Yes. Shared: No. Processed ephemerally: No. Required or optional: Optional (users choose what to upload; automatic backup is opt-in). Purpose: App functionality. "Collected" here means uploaded to the user's own cloud account in encrypted form; the developer has no access.
- **Files and docs**: same answers if you let users add non-media files (the picker allows photos and videos only: answer No).
- **Account info, personal info, location, contacts, messages, financial, health, app activity, web browsing, app info and performance (crash logs, diagnostics), device IDs**: not collected.
- **Security practices**: data encrypted in transit: yes; users can request deletion: yes; independent security review: no; committed to Play Families policy: not applicable.

### Privacy policy URL

Use a page without donation links (the repository page shows a *Sponsor* button). GitHub Pages serves the repo root of `master`, so the policy is always the current `PRIVACY.md`:
`https://rimaturus.github.io/CloakRoll/PRIVACY.html`. The same URL, the home page `https://rimaturus.github.io/CloakRoll/` and the authorized domain `rimaturus.github.io` go in the Google Auth Platform *Branding* page.
