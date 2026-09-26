# Privacy policy

*PhotoVault, last updated 26 September 2026*

**PhotoVault collects no personal data.** The developer receives nothing from the app: no analytics, no crash reports, no identifiers, no usage statistics. There are no PhotoVault servers and no PhotoVault accounts, and the app contains no advertising and no third-party libraries or SDKs.

## What the app does with your data

- **Photos and videos you select** are encrypted on your phone (AES-256-GCM) and uploaded, only in encrypted form, to **your own** Amazon Photos account. The encryption key is derived from your password and never leaves your phone.
- **Your Amazon sign-in** happens on Amazon's own web page inside the app. The app does not see or store your Amazon password; it keeps the session cookies Amazon sets, on your phone only, to talk to your Amazon Photos account.
- **On your phone** the app stores, in its private storage: the list of your items and small previews (both encrypted with your vault key), encrypted downloads used as a cache, and settings (vault salt, a password-check value, and optionally your key wrapped by a fingerprint-protected hardware key). This data is excluded from Android backups and device transfers.
- **Amazon** receives the encrypted files and sees their number, size and upload time, plus the usual information from using your Amazon account. Amazon's handling of that is governed by Amazon's own privacy notice. PhotoVault is not affiliated with Amazon.

## Data sharing and selling

None. The app never sends data to the developer or to any third party other than Amazon, and only in encrypted form.

## Deleting your data

Delete items in the app (they go to your Amazon Photos trash), or delete the `PhotoVault` folder on the Amazon Photos website. Uninstalling the app removes everything it stored on the phone.

## Contact

Questions: open an issue at https://github.com/rimaturus/PhotoVault
