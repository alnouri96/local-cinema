# Local Cinema Manager

Start with [root AGENTS.md](../AGENTS.md). Gradle root: `Phone/`. Package ID: `com.meedx.localcinema.manager`.

## Phone file map

Base package: `Phone/app/src/main/java/org/proninyaroslav/libretorrent/`.

- **TV Hub, Casting & Services (`cinema_hub/`):** `TvHubActivity.java` (coordinator & WS client), `TvHubConnectionController.java` (pairing, Trakt PKCE, addons, keys, Live TV sync, cloud/local backup), `TvHubLibraryController.java`, `TvHubMusicController.java`, `TvHubSubtitlesController.java`, `LocalCinemaBackupManager.java`, `PhoneWatchStore.java`, `ApiKeyStore.java`, `SubtitleMatch.java`, `PhoneVideoServer.java`, `MobileLibraryServer.java`, `CinemaCacheManager.java`, `CinemaStorage.java`, `LocalMediaPanel.java`, `PhotoCastViewer.java`, `AudioSort.java`, `PlaybackArtworkView.java`, `PlaybackSeekBar.java`, `CinemaGlassUi.java`, `CinemaSnackBanner.java`, `MediaPlaybackNotificationService.java`, `PhonePermissionsWizardDialog.java`, `TvQrScannerActivity.java`, `ShareMediaActivity.java`, `VideoTorrentDefaults.java`
- **Floating Remote & Touchpad (`cinema_hub/`):** `FloatingTvDpadController.java`, `FloatingRemoteLayout.java`, `CinemaRemoteBridge.java`, `RemoteJoystick.java`
- **GeckoView Browser & Extensions (`cinema_hub/` & `assets/`):** `CinemaBrowser.java`, `CinemaBrowserSettingsSheet.java`, `CinemaBrowserMediaBridge.java`, `BrowserExtensions.java`, `BrowserTabsSheet.java`, `BrowserDownloadManager.java`, `SiteFaviconManager.java`, `ui/NavBarFragment.java`; WebExtension scripts in `Phone/app/src/main/assets/cinema-video/`: `discover.js`, `network.js`
- **Settings & Torrent Engine (`ui/settings/` & `core/model/`):** `SettingsHeaderFragment.java`, `GoogleAccountHeaderPreference.java`, `StorageCacheHeaderPreference.java`, `SettingsFragment.java`, `CustomPreferenceFragment.java`; torrent core in `core/model/`: `TorrentEngine.java`, `session/TorrentSessionImpl.java`, `stream/TorrentStreamServer.java`
- **Resources & Shared:** `Phone/app/src/main/res/values/strings.xml`, `Phone/app/src/main/res/values-ar/strings.xml`, `../shared-providers/`, `../shared-updater/`.

## Build

Follow [docs/RELEASE.md](../docs/RELEASE.md). Build with `:app:assembleBaseRelease` (staging selects `arm64-v8a`).

## Phone guardrails

- **Modularity & Reuse:** Keep new Phone features isolated in dedicated classes under `cinema_hub/` or `ui/settings/` and add them to this map immediately; avoid duplicating logic already provided by `shared-providers/` or existing controllers.
- **Isolation & Browser:** Preserve `Library` vs `Local media` isolation, the standalone Music Player / Mini Bar, and GeckoView custom features (stream discovery/casting, torrent/magnet routing, page zoom, unified `Extensions` sheet).
- **Quiet Cloud Sync:** Update inline status cards for cloud backup state; suppress redundant bottom snackbars or routine background sync notifications.
