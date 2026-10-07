# Privacy Policy

Last updated: 2026-10-07

XY reader ("the App") is a local manga & novel reading tool. Privacy comes first in its design: **by default no data is collected or uploaded, and all information stays on your own device.** Content is sent to the author only when you explicitly submit a "Bug report".

## Data Collection

The App does not proactively collect any personal information:

- No account system — no registration or sign-in required;
- No ads, no analytics, no automatic crash reporting, or any other third-party tracking components;
- By default no data is uploaded to the author or any third-party server; feedback content is sent only when you explicitly submit it under "Settings → Bug report" (see "Network Communication").

## Local Data

The following data is stored only on your device:

- Library records, reading progress, bookmarks, groups, and reading settings;
- Remote repository (WebDAV) server addresses and credentials;
- Google Drive OAuth tokens;
- Runtime logs: kept in the App's cache directory, at most about 2 MB, automatically rotated and overwritten; passwords, tokens and link parameters are stripped before writing, and logs are never uploaded automatically.

Uninstalling the App removes all local data (runtime logs can also be removed separately with "Clear cache" in the system settings).

## Network Communication

Network requests are made only in the following cases:

- **WebDAV repositories** (triggered by your actions): connects directly to the server address you enter to browse directories and read books;
- **Google Drive** (triggered by your actions): reads files within your authorized scope (`drive.readonly` — read-only) via Google's official APIs;
- **Update check**: after you reach the home screen (skipped if the last successful check was less than 24 hours ago) and when you tap it under "Settings → Version", the App asks GitHub (`api.github.com`) for the latest release of the App. The request carries none of your personal data; GitHub sees your IP address as it would for any network request. The installer is downloaded from GitHub only after you confirm the update;
- **Bug report**: only when you explicitly submit one, the App sends your description, version and device information, and the local runtime log (redacted) to the author's feedback relay service, which stores them in the author's private GitHub repository, for troubleshooting only.

Other than the above, the App performs no network communication.

## Permissions

- **Network access (INTERNET)**: used to connect to the remote repositories you configure, to check for and download updates, and for bug reports you explicitly submit;
- **Install unknown apps (REQUEST_INSTALL_PACKAGES)**: used only to launch the system installer for an update package you have confirmed;
- **File & folder access**: granted through the system file picker; the App can only access files you explicitly select.

## Third-Party Services

When you use Google Drive features, related data access is also governed by Google's privacy policy. Authorization can be revoked at any time in your Google Account under "Third-party apps & services". Update checks and installer downloads are served by GitHub; bug reports are relayed through Cloudflare Workers into a private GitHub repository, so the related data is also governed by the privacy policies of GitHub and Cloudflare.

## Policy Updates

Any changes to this policy will be described in the app's release notes. For questions, please reach out via [GitHub Issues](https://github.com/TerryYu12/xy-reader/issues).
