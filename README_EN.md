# XY reader

**A local manga & novel reader for Android** — read archives without extracting, browse local and remote libraries (WebDAV / Google Drive), and enjoy a clean, distraction-free reading experience.

**English** | [简体中文](README.md)

[![Android CI](https://github.com/TerryYu12/xy-reader/actions/workflows/android-ci.yml/badge.svg)](https://github.com/TerryYu12/xy-reader/actions/workflows/android-ci.yml)

## Features

### Supported formats

| Type | Formats | Notes |
| --- | --- | --- |
| Manga | ZIP / CBZ · RAR / CBR · 7Z / CB7 · TAR / CBT | Read straight from the archive — no extraction, no extra storage |
| E-books | PDF · EPUB · MOBI · AZW3 / KF8 | Paged reading |
| Novels | TXT | Automatic chapter detection + typography engine |
| Images | Folders | Use an image folder directly as a book |

### Library management

- **Local repositories**: add any local folder as a repository and scan it into your library; multiple repositories supported;
- **Remote repositories**:
  - WebDAV — works with Nutstore (坚果云), Alist, InfiniCLOUD and similar services;
  - Google Drive — OAuth authorization (read-only scope); ZIP / 7Z / TAR are streamed page-by-page on demand, RAR / PDF are downloaded to cache on first open;
- **Organization**: groups, favorites, reading history, and bookmarks in one place;
- **Covers**: extracted automatically from archives, with support for a custom cover filename;
- Book detail page: table of contents, page count, and reading progress at a glance.

### Reader

- Page modes: left-right pagination / vertical scrolling (great for text novels);
- Manga direction: Western (left-to-right) / Japanese (right-to-left);
- Gestures: tap sides to turn pages (can be disabled), double-tap and pinch to zoom;
- Display: brightness control, keep screen on, orientation lock (system / portrait / landscape);
- Reading background: pure black / dark gray / eye-care sepia / white;
- Image scaling: fit screen / fit width;
- Novel typography: font size, weight, line spacing, margins, letter spacing, first-line indent, chapter starts on a new page;
- Fonts: built-in LXGW WenKai / MiSans / Zhuque Fangsong, plus import of custom fonts (ttf / otf / ttc);
- Table of contents, bookmarks, reading progress memory, text copy.

### System integration

- **Open with**: open zip / cbz / cbr / 7z / tar / pdf / epub / mobi / txt files directly in XY-READER from file managers, MT Manager, QQ, WeChat, etc.;
- **Share to import**: share a file from any app to XY-READER and it lands in your library, ready to read.

## Installation

### Download (recommended)

Get the latest `XY-READER-<version>.apk` from the [Releases](https://github.com/TerryYu12/xy-reader/releases) page.

- Requires **Android 8.0 (API 26)** or newer;
- Released APKs are signed with a debug key (so they can be installed directly). The signature differs from packages built elsewhere, so installing over them requires uninstalling the old version first.

### Build from source

Requires JDK 17 and the Android SDK (compileSdk 36):

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Build the release APK
# Output: app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleRelease
```

The `outputs/` directory is not tracked (APKs can be rebuilt from source at any time). CI (GitHub Actions) runs tests and builds the APK automatically on every push to `main`; artifacts can be downloaded from the Actions page.

## Usage guide

### 1. Import local books

1. Open the **Shelf** tab and tap **+** in the top-right corner to pick the folder that holds your manga / novels (system file picker);
2. Wait for the scan to finish — books are added to your library automatically;
3. Repeat with other folders to manage multiple repositories side by side.

### 2. Start reading

- Tap a cover on the Home or Shelf tab to start reading; the floating **Start reading** menu in the bottom-right corner lets you resume or start over quickly;
- While reading, tap the **center** of the screen to bring up the toolbar: table of contents, bookmarks, brightness, typography, copy text, etc.;
- In left-right mode, tap the **sides** of the screen to turn pages (can be disabled in settings); double-tap or pinch to zoom on manga pages.

### 3. Reading settings

Open from the reading toolbar, or go to **Settings → Reading configuration**. Settings are grouped into three tabs:

- **Page mode**: left-right / vertical scrolling, manga direction, screen orientation;
- **Page**: background color, brightness, image scaling, tap-to-turn, double-tap zoom, keep screen on;
- **Fonts** (text novels only): font family, size, weight, line spacing, margins, letter spacing, first-line indent, chapter starts on a new page — plus custom font import.

### 4. Add a WebDAV repository

1. Go to **Settings → Remote repositories** and tap **+**;
2. Enter the server address, username and password. For example, Nutstore (坚果云) uses `https://dav.jianguoyun.com/dav/` — the password must be an **app password** (Nutstore: Account info → Security options → Add app password), not your login password. Alist, InfiniCLOUD and similar services work the same way;
3. Tap **Test connection**; once it succeeds, save and tap **Scan** to bring cloud books into your library.

### 5. Add a Google Drive repository

1. Follow [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md) to create your own OAuth client (desktop app type; takes about 10 minutes, one time only) in your Google Cloud console;
2. Go to **Settings → Google Drive (beta)**, tap **+** and fill in a name, Client ID and Client Secret (optionally a target folder ID; leave empty to scan all of My Drive);
3. Tap **Authorize and save**, complete authorization in the browser (read-only scope only), then tap **Scan** back in the app.

### 6. Open from other apps (Open with / Share)

- File manager / MT Manager: long-press or select a file → Open with → XY-READER;
- In other apps: Share → XY-READER;
- Also works for opening files directly from archive managers.

## Privacy

- **No data is collected**: no ads, no analytics, no telemetry. The app makes no network requests except to the remote repositories you configure yourself;
- Library data, reading progress and bookmarks are stored only on your device;
- Remote repository addresses and credentials are stored only on your device;
- Google Drive access uses the `drive.readonly` scope only — the app cannot modify anything in your drive.

The full privacy policy is available at [PRIVACY_EN.md](PRIVACY_EN.md) (the same text appears in the app under Settings → Privacy Policy).

## Credits & acknowledgements

### Open-source components

| Component | Purpose | License |
| --- | --- | --- |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) / [AndroidX](https://developer.android.com/jetpack) | UI & foundations | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/) | Language | Apache-2.0 |
| [Room](https://developer.android.com/training/data-storage/room) | Local database | Apache-2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Cover image loading | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Networking (WebDAV / range streaming) | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | ZIP / 7Z / TAR parsing | Apache-2.0 |
| [junrar](https://github.com/junrar/junrar) | RAR extraction | UnRAR License (extraction only; not for creating RAR archives) |

### Bundled fonts

| Font | Source | License |
| --- | --- | --- |
| LXGW WenKai Lite | [LXGW WenKai](https://github.com/lxgw/LxgwWenKai) | SIL OFL 1.1 |
| MiSans | © Xiaomi | Xiaomi font license (free for commercial use) |
| Zhuque Fangsong | Xuanji Type | SIL OFL 1.1 |

Font files are bundled with the APK. Custom fonts imported by the user are stored on-device only; copyright remains with their authors.

### Special thanks

Parts of this project's design (chapter pagination, reader interaction behavior, and more) were informed by the publicly available implementations of these open-source projects:

- [Legado (阅读)](https://github.com/gedoor/legado)
- [KOReader](https://github.com/koreader/koreader)
- [Librera Reader](https://github.com/librera/LibreraReader)

## Disclaimer

1. This software is a **purely local / private-storage reading tool**. It does not provide, bundle, or host any book sources, manga, or novel content;
2. All content you open through this software comes from your own device or personal network storage; copyright remains with the original authors and rights holders;
3. Please make sure the content you access with this software comes from legitimate sources. Any copyright disputes or legal liabilities arising from the use of this software are borne by the user;
4. This software is provided "AS IS", without warranty of any kind, express or implied. The author is not liable for any data loss or other damages caused by the use of this software;
5. If you enjoy a work, please support the official release.

## License

[MIT License](LICENSE)
