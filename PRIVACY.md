# Privacy policy

*Cloakroll (formerly PhotoVault), last updated 1 October 2026 (version 1.9.1)*

**Cloakroll collects no personal data.** The developer receives nothing from the app: no analytics, no crash reports, no identifiers, no usage statistics. There are no Cloakroll servers and no Cloakroll accounts, and the app contains no advertising and no third-party libraries or SDKs.

## What the app does with your data

- **Photos and videos you select** are encrypted on your phone (AES-256-GCM) and uploaded, only in encrypted form, to **your own** storage: Amazon Photos, Microsoft OneDrive, Google Drive, or a folder on your phone, whichever you choose. You can also keep an encrypted copy in a folder on your phone next to the cloud. The encryption key is derived from your password and never leaves your phone.
- **Amazon sign-in** happens on Amazon's own web page inside the app. The app does not see or store your Amazon password; it keeps the session cookies Amazon sets, on your phone only, to talk to your Amazon Photos account.
- **Microsoft sign-in** (OneDrive) happens on Microsoft's own page in your browser. The app does not see your Microsoft password. Microsoft gives the app a token that opens only the app's own OneDrive folder (`Apps/PhotoVault`), unless you choose full OneDrive access during setup. The token is stored on your phone only, encrypted with a key in the phone's secure hardware.
- **Google sign-in** (Google Drive) happens on Google's own page in your browser. The app does not see your Google password. Google gives the app a token limited to `drive.file`: the app can open only the files it created itself (the folder `Cloakroll`), none of your other files. The token is stored on your phone only, encrypted with a key in the phone's secure hardware.
- **Automatic backup** (off unless you turn it on) asks for permission to read your photos and videos, so it can find new ones and encrypt them; nothing else is done with that access. To encrypt while the vault is locked, a copy of your vault key is kept on the phone, sealed by the phone's secure hardware; opening the vault still needs your password. Turning automatic backup off deletes that copy. *Free up space* deletes, from the phone only, files the vault already holds, and only after Android's own confirmation dialog.
- **A folder on your phone** (if you keep the vault only on the phone, or a copy of it) is chosen with Android's folder picker; the app gets access to that folder only, and writes only encrypted files there.
- **Transfer statistics** (speeds and times of uploads, downloads, encryption and decryption) are kept in the app's settings on your phone only, to show time estimates. They are never sent anywhere.
- **On your phone** the app stores, in its private storage: the list of your items and small previews (both encrypted with your vault key), encrypted downloads used as a cache, and settings (vault salt, a password-check value, and optionally your key wrapped by a fingerprint-protected hardware key). This data is excluded from Android backups and device transfers.
- **The list of your items** (names, dates, sizes) and your folders are also saved in your storage, in one more file encrypted with your vault key, so a new phone can restore them without downloading every file. Deleting an item removes it from that list.
- **Amazon, Microsoft or Google** receives the encrypted files and sees their number, size and upload time, plus the usual information from using your account. Their handling of that is governed by their own privacy notices. Cloakroll is not affiliated with Amazon, Microsoft or Google.

## Data sharing and selling

None. The app never sends data to the developer or to any third party other than the storage provider you chose (Amazon, Microsoft or Google), and only in encrypted form.

## Deleting your data

Delete items in the app (they go to your Amazon Photos trash, OneDrive recycle bin, Google Drive trash, or the `Trash` folder inside the vault folder on your phone), or delete the vault folder itself (`PhotoVault` on Amazon Photos, `Apps/PhotoVault` on OneDrive, `Cloakroll` on Google Drive or on your phone). Signing out in the app deletes the OneDrive or Google token from the phone; to also withdraw the permission you gave, remove Cloakroll at https://account.live.com/consent/Manage (personal Microsoft accounts) or https://myaccount.google.com/connections (Google). Uninstalling the app removes everything it stored in its private storage; files in a folder you chose on the phone stay there until you delete them.

## Contact

Questions: write to rimaturus@gmail.com or open an issue at https://github.com/rimaturus/PhotoVault
