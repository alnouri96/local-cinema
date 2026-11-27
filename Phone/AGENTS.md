# Local Cinema Manager (`Phone/`)

Start with [root AGENTS.md](../AGENTS.md) and [HANDOFF.md](../HANDOFF.md). Gradle root: `Phone/`. Package ID: `com.meedx.localcinema.manager`.
Base Java path: `Phone/app/src/main/java/org/proninyaroslav/libretorrent/`.

## Module Isolation Map (`v2`-Ready 3-Module Architecture)

To enable lifting **Browser** and **TV Hub** cleanly into a brand-new `v2` Phone app without any `LibreTorrent` dependency, `Phone/` is partitioned into three strictly isolated modules connected solely through `cinema_hub/PhoneModuleBridges.java`:

1. **Module A — `Browser` (`cinema_hub/` Browser set + `assets/cinema-video/`):**
   - **Files:** `CinemaBrowser.java`, `CinemaBrowserMediaBridge.java`, `CinemaMediaDownloader.java`, `CinemaBrowserSettingsSheet.java`, `BrowserExtensions.java`, `BrowserTabsSheet.java`, `BrowserDownloadManager.java`, `SiteFaviconManager.java`, plus `CinemaGlassUi.java` / `CinemaSnackBanner.java`.
   - **Boundary:** **Zero** compile-time references to `LibreTorrent` (`MainActivity`, `TorrentEngine`, etc.) and **zero** direct references to `TvHub*`, `PhoneVideoServer`, or `CinemaCacheManager`. Talks outward exclusively via `PhoneModuleBridges.tvHub()` (`BrowserCastPayload`, subtitle registration, `notifyLibraryDownloadUpdated`, Stremio `stremio://` handoff) and exposes cache/bookmark/header-fetch hooks via `PhoneModuleBridges.BrowserModulePort`.
2. **Module B — `TV Hub / مركز التحكم بالشاشة` (`cinema_hub/` Hub, Servers, Subtitles, Music, Remote, Backup & Cache):**
   - **Files:** `TvHubActivity.java`, `TvHubConnectionController.java`, `TvHubSubtitlesController.java`, `TvHubLibraryController.java`, `TvHubMusicController.java`, `CuratedAddonsSheet.java`, `LocalCinemaBackupManager.java`, `CinemaCloudSyncWorker.java`, `PhoneVideoServer.java`, `MobileLibraryServer.java`, `CinemaStorage.java`, `CinemaCacheManager.java`, `FloatingTvDpadController.java`, `FloatingRemoteLayout.java`, `CinemaRemoteBridge.java`, `RemoteJoystick.java`, `LocalMediaPanel.java`, `PhotoCastViewer.java`, `ShareMediaActivity.java`, `TvQrScannerActivity.java`, `PhonePermissionsWizardDialog.java`, `PhoneWatchStore.java`, `AudioSort.java`, `PlaybackArtworkView.java`, `PlaybackSeekBar.java`, `MediaPlaybackNotificationService.java`.
   - **Boundary:** **Zero** compile-time references to `LibreTorrent` (`MainActivity`, `TorrentEngine`, `TorrentStream`, `TorrentMetaInfo`, `BencodeFileItem`, `AddTorrentMutableParams`, `core/*`, `ui/*`, `service/*`) and **zero** direct references to `CinemaBrowser*` / `BrowserDownloadManager`. Optional torrent casting and host tab switching execute via `PhoneModuleBridges.torrentCast()` (`ByteRangeStreamSource`) and `PhoneModuleBridges.hostShell()`, which degrade gracefully to no-ops when `LibreTorrent` is absent in `v2`.
3. **Module C — `LibreTorrent` Download Engine & v1 Host Shell (`MainApplication`, `MainActivity`, `core/`, `service/`, `receiver/`, `ui/`):**
   - **Adapter:** `ui/LibreTorrentCinemaBridge.java` (installed in `MainApplication.onCreate()`) is the single adapter binding `MainActivity` + `TorrentEngine` + sequential video piece defaults into `PhoneModuleBridges`.
   - **Settings Split:** `ui/settings/SettingsHeaderFragment.java` shows **only** `Local Cinema Manager` settings (TV Sync, Cloud Backup, Subtitles, Cache Cleaner, Permissions, App Updates, About & Support/Donate) plus one entry to `ui/settings/TorrentEngineSettingsFragment.java` («إعدادات محرك التحميل (LibreTorrent)»), which isolates all 9 LibreTorrent sections (Storage, Appearance, App Language, Behavior, Streaming, Network, Limitations, Scheduling, RSS).

## Functional Phone File Map

### 1. Inter-Module Boundary & Host Adapter
- `cinema_hub/PhoneModuleBridges.java` — Pure-Android port registry (`HostShellPort`, `TorrentCastPort`, `ByteRangeStreamSource`, `BrowserModulePort`, `TvHubModulePort`, `BrowserCastPayload`) isolating `Browser`, `TV Hub`, and `LibreTorrent`.
- `ui/LibreTorrentCinemaBridge.java` — v1 host adapter wiring `MainActivity`, `TorrentEngine`/`TorrentStream`/`TorrentMetaInfo`, and sequential video piece defaults into `PhoneModuleBridges`.

### 2. TV Hub Coordinator, Pairing, Sync & Cloud Backup (`cinema_hub/`)
- `TvHubActivity.java` — Main 3-tab companion coordinator (`Connection`, `Subtitles`, `Library & Cast`), WebSocket client (`8765`), connection lifecycle, and `TvHubModulePort` implementation.
- `TvHubConnectionController.java` — TV QR/IP pairing card, Trakt OAuth 2.0 `S256` PKCE flow, API keys push (`TMDB`, `OMDB`, `SUBSOURCE`, `OPEN_SUBTITLES`), Stremio add-ons management, Live TV country sync, and manual sync triggers.
- `CuratedAddonsSheet.java` — Bilingual (`RTL`/`LTR`) curated Stremio add-ons `BottomSheetDialog` with live `مثبّتة ✓` status from TV.
- `LocalCinemaBackupManager.java` & `CinemaCloudSyncWorker.java` — Unified local JSON & Google Drive (`appDataFolder`) cloud backup/restore with separated `tv_settings` vs `phone_settings` timestamps/hashes, `Timestamp Conflict Guard` (pulls newer live TV settings instead of overwriting; pushes cloud to empty/older TV), 20s debounced silent Drive uploads on Phone/TV mutations, handshake reconciliation, and 6-hour `WorkManager` periodic sync.
- `TvQrScannerActivity.java`, `PhonePermissionsWizardDialog.java`, `PhoneWatchStore.java` — QR pairing camera scanner, permissions onboarding dialog, and local watched/resume SQLite store.

### 3. Subtitles, Libraries & Local Media / Music Casting (`cinema_hub/`)
- `TvHubSubtitlesController.java` — Phone Unified Subtitle Hub UI (priorities, Subsource/OpenSubtitles/USS/SubSense toggles, live appearance/outline/opacity, two-way TV sync).
- `TvHubLibraryController.java` — Streamlined «المكتبات» (`Libraries`) tab showing Phone `Local Cinema` folders (`Anime`, `Download`, `Movies`, `TV Shows`) + USB/storage picker directly without Phone-side Trakt list sub-tabs.
- `TvHubMusicController.java` — Standalone Phone Music Player & TV audio caster with queue, shuffle/repeat, and mini-player bar.
- `LocalMediaPanel.java`, `PhotoCastViewer.java`, `ShareMediaActivity.java` — Local video/audio/photo picker, swipeable photo caster, and Android `ACTION_SEND` cast receiver.
- `AudioSort.java`, `PlaybackArtworkView.java`, `PlaybackSeekBar.java`, `MediaPlaybackNotificationService.java` — Music sorting, artwork view, scrub bar, and foreground `MediaSession` service.

### 4. Local HTTP/WebDAV Servers, Storage & Cache (`cinema_hub/`)
- `PhoneVideoServer.java` — Local HTTP byte-range media & HLS/DASH proxy server streaming phone files, in-memory subtitles, and abstract `ByteRangeStreamSource` streams to TV.
- `MobileLibraryServer.java` — Port `8080` WebDAV server exposing phone video folders as an indexable TV library source.
- `CinemaStorage.java` & `CinemaCacheManager.java` — Scoped/SAF storage helpers (`Local Cinema/Download` with automatic non-destructive migration from legacy `Local Cinema/Downloading`) and safe cache/temp-buffer analyzer & cleaner (`formatBytes`, 120 MB Gecko / 60 MB app caps).

### 5. Floating TV Remote & Touchpad (`cinema_hub/`)
- `FloatingTvDpadController.java`, `FloatingRemoteLayout.java`, `CinemaRemoteBridge.java`, `RemoteJoystick.java` — Lifecycle-scoped floating D-pad/Touchpad remote overlay, WebSocket (`8765`) dispatcher, and directional joystick.

### 6. GeckoView Browser, Universal Media Downloader & WebExtensions (`cinema_hub/`, `assets/`)
- `CinemaBrowser.java` — Built-in GeckoView browser with multi-tab management, custom `#171717`/`#E5A93B` vector-icon overflow menu (`⋮`), unified `UI_MODE_NIGHT_YES` / `COLOR_SCHEME_DARK` context, `#171717` viewport-backed crash-recovery anti-black-screen lifecycle, ad/popup blocking, page zoom, and `BrowserModulePort` registration (no `SwipeRefreshLayout` and no RSS button in top bar).
- `CinemaBrowserMediaBridge.java` — Sniffs direct video streams (`MP4`/`MKV`/`WEBM`/HLS/DASH with `Referer`/`Origin`/`Cookie` headers) and torrent/`magnet:` links, places a golden `↓` download button beside `CC` and `▶` on every stream quality, rejects cloaked/decoy financial/ad titles (`elif.news` etc.) while recovering real titles from player `iframe` / inline player config / opener tab / URL hints, filters pre-roll ads & `#xywh=` storyboard VTTs, and hands off casts via `PhoneModuleBridges.tvHub()`.
- `CinemaMediaDownloader.java` & `BrowserDownloadManager.java` — Universal stream downloader (direct progressive HTTP with 8-hop `302` redirect + session header/cookie jar forwarding, adaptive `HLS / .m3u8` Master/Media variant download + `AES-128` decryption + lossless `.mp4` remuxing, and split `video+audio` muxing) saving `<CleanTitle> - <Quality>.mp4` into `Local Cinema/Download` with live system notifications and instant `Libraries` refresh via `PhoneModuleBridges.tvHub()`.
- `CinemaBrowserSettingsSheet.java`, `BrowserExtensions.java`, `BrowserTabsSheet.java`, `SiteFaviconManager.java` — Dark-styled browser settings, WebExtensions manager, visual tab switcher, and favicon cache.
- `Phone/app/src/main/assets/cinema-video/discover.js` & `network.js` — Built-in WebExtension scripts for decoy-proof title extraction, pre-roll ad & `#xywh=` filtering, and stream/magnet detection.

### 7. UI Primitives, Settings Split, RSS & Torrent Host (`cinema_hub/`, `ui/`, `core/`)
- `CinemaGlassUi.java` & `CinemaSnackBanner.java` — Shared `#171717` / `#E5A93B` Material cards and status banners.
- `ui/settings/SettingsHeaderFragment.java`, `GoogleAccountHeaderPreference.java`, `StorageCacheHeaderPreference.java`, `SettingsFragment.java`, `CustomPreferenceFragment.java`, `ui/home/AboutDialog.java` — Clean `Local Cinema Manager` main settings screen & About/Donate dialog.
- `ui/settings/TorrentEngineSettingsFragment.java` — Isolated «إعدادات محرك التحميل (LibreTorrent)» sub-screen containing all 9 LibreTorrent preference sections.
- `ui/settings/CloudBackupDialog.java` — Dark bilingual backup options and confirmation dialogs. `res/drawable/ic_backup_{cloud,cloud_upload,cloud_restore,local_save,local_restore,sign_out}.xml` provides the action icons.
- `ui/settings/pages/FeedSettingsFragment.java`, `ui/feeds/FeedsActivity.java`, `ui/feeds/FeedFragment.java` — RSS settings with top header «قنوات وخلاصات RSS / RSS Feeds» Gold button opening `FeedsActivity` / `FeedFragment` with Back return.
- `ui/NavBarFragment.java`, `ui/PlaybackBubble.java`, `core/model/TorrentEngine.java`, `core/model/session/TorrentSessionImpl.java`, `core/model/stream/TorrentStreamServer.java` — v1 bottom navigation host, cast bubble, and `libtorrent4j` engine.
- **Resources:** `Phone/app/src/main/res/values/strings.xml`, `cinema_hub_strings.xml` (+ `values-ar/`).

## Build & Phone Guardrails
- **Build:** Follow [docs/RELEASE.md](../docs/RELEASE.md) sequentially (`.\gradlew.bat :app:assembleBaseRelease --no-daemon --max-workers=2`; staging selects `arm64-v8a`).
- **Isolation Law:** Never add `LibreTorrent` imports inside `cinema_hub/`, and never add `TvHub*` imports inside `Browser` classes — route strictly through `PhoneModuleBridges.java` and keep `AGENTS.md` under **240 lines**.
