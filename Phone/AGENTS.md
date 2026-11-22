# Local Cinema Manager

Start with [the root agent guide](../AGENTS.md). The Phone Gradle root is `Phone/` and the package ID is `com.meedx.localcinema.manager`.

## Phone-Only File Map

- **Core Hub & Casting (`Phone/app/src/main/java/org/proninyaroslav/libretorrent/cinema_hub/`):**
  - Coordinator, Lifecycle, WebSocket Transport & Video Casting: `TvHubActivity.java`
  - Standalone Music Player & Mini Bar: `TvHubMusicController.java`
  - Active/Stream Subtitles, Dual Position & Search: `TvHubSubtitlesController.java`, `SubtitleMatch.java`
  - Isolated Library, Eye Toggle, USB & Watchlist: `TvHubLibraryController.java`, `PhoneWatchStore.java`, `MobileLibraryServer.java`
  - Pairing, Trakt PKCE, Addons, API Keys & Unified Cloud/Local Backup: `TvHubConnectionController.java`, `ApiKeyStore.java`, `LocalCinemaBackupManager.java`
  - TV Remote & Touchpad: `FloatingTvDpadController.java`, `FloatingRemoteLayout.java`, `CinemaRemoteBridge.java`, `RemoteJoystick.java`
  - Media / Photo Casting, Storage Cache & Services: `PhoneVideoServer.java`, `CinemaCacheManager.java`, `LocalMediaPanel.java`, `PhotoCastViewer.java`, `CastMediaForegroundService.java`, `MediaPlaybackNotificationService.java`, `CinemaGlassUi.java`, `../ui/settings/StorageCacheHeaderPreference.java`
- **GeckoView Browser & Extensions:**
  - Coordinator, Lifecycle, Viewport/Desktop/Zoom & Tabs: `CinemaBrowser.java`
  - Browser Settings Sheet, Switches & Page Zoom Slider (`50%–150%`): `CinemaBrowserSettingsSheet.java`
  - Video/Subtitle Discovery, Probing, Quality/Dual-Subtitle Sheets & TV Cast Handoff: `CinemaBrowserMediaBridge.java`
  - Extensions, Tabs, Downloads & Favicons: `BrowserExtensions.java`, `BrowserTabsSheet.java`, `BrowserDownloadManager.java`, `SiteFaviconManager.java`, `../ui/NavBarFragment.java`
  - WebExtension Content/Background Scripts (`Phone/app/src/main/assets/cinema-video/`): `discover.js`, `network.js`
- **Shared Modules Used:** `../shared-providers/` (`TraktSyncClient.java`, `StremioAddons.java`, `ProviderKeys.java`, `ZeroConfigSubtitles.java`) and `../shared-updater/`.

## Build and test

Follow [the monorepo release procedure](../docs/RELEASE.md) for signed builds and both ADB targets. Use `:app:assembleBaseRelease` in this module. The staging script selects the arm64-v8a APK.

## Phone Guardrails

- Add every new user-facing label, button, title, message, dialog and accessibility description to both English `values/strings.xml` and Arabic `values-ar/strings.xml` in the owning app. Use resource IDs in code and preserve RTL/LTR behavior.
- Trakt OAuth uses PKCE only (`client_id`: `ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE`, `localcinema://trakt-callback`, NO `client_secret`, Developer key UI hidden).
- Phone-to-TV `FULL_SYNC_STATE` & Trakt syncs trigger ONLY on settings/addon/key changes or manual user tap — no auto-sync on resume/connect.
- Preserve `Library` vs `Local media` isolation, the separate Music Player / Mini Bar, and custom browser features (video stream detection/casting, torrent/magnet routing, page zoom, unified `Extensions` sheet).
