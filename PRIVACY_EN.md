# Privacy Policy

Last updated: 2026-09-30

XY reader ("the App") is a local manga & novel reading tool. Privacy comes first in its design: **no data is collected, and all information stays on your own device.**

## Data Collection

The App does not collect any personal information:

- No account system — no registration or sign-in required;
- No ads, no analytics, no crash reporting, or any other third-party tracking components;
- No data is ever sent to the author or any third-party server.

## Local Data

The following data is stored only on your device:

- Library records, reading progress, bookmarks, groups, and reading settings;
- Remote repository (WebDAV) server addresses and credentials;
- Google Drive OAuth tokens.

Uninstalling the App removes all local data.

## Network Communication

Network requests are made only in the following cases, always triggered directly by your actions and addressed to servers you specify:

- **WebDAV repositories**: connects to the server address you enter to browse directories and read books;
- **Google Drive**: reads files within your authorized scope (`drive.readonly` — read-only) via Google's official APIs.

Other than the above, the App performs no network communication.

## Permissions

- **Network access (INTERNET)**: used only to connect to the remote repositories you configure;
- **File & folder access**: granted through the system file picker; the App can only access files you explicitly select.

## Third-Party Services

When you use Google Drive features, related data access is also governed by Google's privacy policy. Authorization can be revoked at any time in your Google Account under "Third-party apps & services".

## Policy Updates

Any changes to this policy will be described in the app's release notes. For questions, please reach out via [GitHub Issues](https://github.com/TerryYu12/xy-reader/issues).
