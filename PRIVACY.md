# Privacy policy

*PhotoVault, last updated 27 September 2026*

**PhotoVault collects no personal data.** The developer receives nothing from the app: no analytics, no crash reports, no identifiers, no usage statistics. There are no PhotoVault servers and no PhotoVault accounts, and the app contains no advertising and no third-party libraries or SDKs.

## What the app does with your data

- **Photos and videos you select** are encrypted on your phone (AES-256-GCM) and uploaded, only in encrypted form, to **your own** storage account: Amazon Photos or Microsoft OneDrive, whichever you choose. The encryption key is derived from your password and never leaves your phone.
- **Amazon sign-in** happens on Amazon's own web page inside the app. The app does not see or store your Amazon password; it keeps the session cookies Amazon sets, on your phone only, to talk to your Amazon Photos account.
- **Microsoft sign-in** (OneDrive) happens on Microsoft's own page in your browser. The app does not see your Microsoft password. Microsoft gives the app a token that opens only the app's own OneDrive folder (`Apps/PhotoVault`), unless you choose full OneDrive access during setup. The token is stored on your phone only, encrypted with a key in the phone's secure hardware.
- **On your phone** the app stores, in its private storage: the list of your items and small previews (both encrypted with your vault key), encrypted downloads used as a cache, and settings (vault salt, a password-check value, and optionally your key wrapped by a fingerprint-protected hardware key). This data is excluded from Android backups and device transfers.
- **Amazon or Microsoft** receives the encrypted files and sees their number, size and upload time, plus the usual information from using your account. Their handling of that is governed by their own privacy notices. PhotoVault is not affiliated with Amazon or Microsoft.

## Data sharing and selling

None. The app never sends data to the developer or to any third party other than the storage provider you chose (Amazon or Microsoft), and only in encrypted form.

## Deleting your data

Delete items in the app (they go to your Amazon Photos trash or OneDrive recycle bin), or delete the vault folder on the provider's website (`PhotoVault` on Amazon Photos, `Apps/PhotoVault` on OneDrive). Signing out in the app deletes the OneDrive token from the phone; to also withdraw the permission you gave, remove PhotoVault at https://account.live.com/consent/Manage (personal Microsoft accounts) or ask your organisation's administrator. Uninstalling the app removes everything it stored on the phone.

## Contact

Questions: open an issue at https://github.com/rimaturus/PhotoVault
