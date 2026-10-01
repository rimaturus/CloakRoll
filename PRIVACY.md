# Privacy policy

*Cloakroll (formerly PhotoVault), last updated 1 October 2026 (version 1.9.2)*

**Cloakroll collects no personal data.** The developer receives nothing from the app: no analytics, no crash reports, no identifiers, no usage statistics, no advertising IDs. There are no Cloakroll servers and no Cloakroll accounts, and the app contains no advertising and no third-party libraries or SDKs. Everything the app does happens on your phone and between your phone and the storage account you chose.

## Who is responsible

The app is published by its developer, "rimaturus" (Italy), reachable at rimaturus@gmail.com. Because the app sends the developer no data at all, the developer does not process your personal data and is not a data controller for anything you do with the app. Your photos, their encrypted copies and your storage account are processed only by you, on your device, and by the storage provider you chose, under that provider's own terms and privacy notice.

## What the app does with your data

- **Photos and videos you select** are encrypted on your phone (AES-256-GCM, key derived from your password with PBKDF2-HMAC-SHA256, 600,000 rounds) and uploaded, only in encrypted form, to **your own** storage: Amazon Photos, Microsoft OneDrive, Google Drive, or a folder on your phone, whichever you choose. You can also keep an encrypted copy in a folder on your phone next to the cloud. The encryption key never leaves your phone.
- **Amazon sign-in** happens on Amazon's own web page inside the app, on the Amazon site you choose (for example amazon.com or amazon.it). The app does not see or store your Amazon password; it keeps the session cookies Amazon sets, on your phone only, to talk to your Amazon Photos account.
- **Microsoft sign-in** (OneDrive) happens on Microsoft's own page in your browser. The app does not see your Microsoft password. Microsoft gives the app a token that opens only the app's own OneDrive folder (`Apps/PhotoVault`), unless you choose full OneDrive access during setup. The token is stored on your phone only, encrypted with a key in the phone's secure hardware.
- **Google sign-in** (Google Drive) happens on Google's own page in your browser. The app does not see your Google password. Google gives the app a token limited to `drive.file`: the app can open only the files it created itself (the folder `Cloakroll`), none of your other files. The token is stored on your phone only, encrypted with a key in the phone's secure hardware.
- **Automatic backup** (off unless you turn it on) asks for permission to read your photos and videos, so it can find new ones and encrypt them; nothing else is done with that access, and you choose whether it may use mobile data or Wi-Fi only. To encrypt while the vault is locked, a copy of your vault key is kept on the phone, sealed by the phone's secure hardware; opening the vault still needs your password. Turning automatic backup off deletes that copy. *Free up space* deletes, from the phone only, files the vault already holds, and only after Android's own confirmation dialog.
- **A folder on your phone** (if you keep the vault only on the phone, or a copy of it) is chosen with Android's folder picker; the app gets access to that folder only, and writes only encrypted files there.
- **Transfer statistics** (speeds and times of uploads, downloads, encryption and decryption) are kept in the app's settings on your phone only, to show time estimates. They are never sent anywhere.
- **On your phone** the app stores, in its private storage: the list of your items and small previews (both encrypted with your vault key), encrypted downloads used as a cache, an in-memory log of technical events (never file names, cookies or keys; it is gone when the app closes unless you copy it yourself), and settings (vault salt, a password-check value, and optionally your key wrapped by a fingerprint-protected hardware key). This data is excluded from Android backups and device transfers.
- **The list of your items** (names, dates, sizes) and your folders are also saved in your storage, in one more file encrypted with your vault key, so a new phone can restore them without downloading every file. Deleting an item removes it from that list.
- **Amazon, Microsoft or Google** receives the encrypted files and sees their number, size and upload time, plus the usual information from using your account (IP address, device, time). The app sends them nothing else. Their handling of that is governed by their own privacy notices: [Amazon](https://www.amazon.com/privacy), [Microsoft](https://privacy.microsoft.com/privacystatement), [Google](https://policies.google.com/privacy). Cloakroll is not affiliated with Amazon, Microsoft or Google.

## Permissions

Internet (to reach your storage), notifications (upload progress, never file names), foreground service (uploads continue in the background), biometrics (optional fingerprint unlock), network state and boot completed (the automatic backup job waits for the connection you chose and survives a restart), and, only if you turn on automatic backup, photos and videos (Android 14 and later lets you limit it to selected photos). No contacts, location, camera, microphone or general storage access.

## Data sharing and selling

None. The app never sends data to the developer or to any third party other than the storage provider you chose, and only in encrypted form. No data is sold, shared for advertising, or used for profiling.

## Children

Cloakroll is not directed at children. It is intended for users aged 18 and over and is rated accordingly on Google Play.

## Security

All content leaves the phone encrypted with AES-256-GCM, authenticated, so any change to a stored file is detected when it is opened. Connections use HTTPS only. Sign-in tokens and the optional key copy for automatic backup are sealed with keys held in the phone's secure hardware. The app blocks screenshots and hides its content in the recent-apps view unless you allow screenshots in Settings. The source code is public ([github.com/rimaturus/PhotoVault](https://github.com/rimaturus/PhotoVault), GPL-3.0), and the Google Play build carries code transparency, so anyone can verify that the installed code is the published one. The app has not had an independent security audit; reports are welcome through the repository.

## Your rights and data deletion

The developer holds no data about you, so there is nothing for the developer to access, correct, export or erase; you are always in control of everything the app handles. To delete your data: delete items in the app (they go to your Amazon Photos trash, OneDrive recycle bin, Google Drive trash, or the `Trash` folder inside the vault folder on your phone), or delete the vault folder itself (`PhotoVault` on Amazon Photos, `Apps/PhotoVault` on OneDrive, `Cloakroll` on Google Drive or on your phone). Signing out in the app deletes the OneDrive or Google token from the phone; to also withdraw the permission you gave, remove Cloakroll at https://account.live.com/consent/Manage (personal Microsoft accounts) or https://myaccount.google.com/connections (Google). Uninstalling the app removes everything it stored in its private storage; files in a folder you chose on the phone stay there until you delete them. If you lose your password, the encrypted files cannot be recovered by anyone, including the developer.

If you are in the EU/EEA or the UK, the rights under the GDPR (access, rectification, erasure, restriction, portability, objection, complaint to a supervisory authority) apply to any processing by the storage provider under that provider's responsibility; for the app itself there is no processing by the developer to exercise them against. Questions are welcome at the contact below.

## Changes to this policy

Changes are published with the app version that makes them, in this file in the repository and inside the app (Settings > Privacy policy), with the date at the top.

## Contact

Write to rimaturus@gmail.com or open an issue at https://github.com/rimaturus/PhotoVault
