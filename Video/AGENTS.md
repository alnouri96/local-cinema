# Local Cinema TV

Start with [the root agent guide](../AGENTS.md). The TV Gradle root is `Video/` and the package ID is `com.meedx.localcinema.tv`.

## TV-Only File Map

- **Cinema Leanback UI & Receiver (`Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/`):**
  - Home, Library & Lists: `CinemaHomeFragment.java`, `CinemaHomeLoader.java`, `CinemaDetailsActivity.java`, `CinemaSeriesActivity.java`, `CinemaWatchlistActivity.java`, `CinemaWatchStore.java`, `CinemaUi.java`
  - WebSocket Receiver & HLS/Cast Player: `CinemaLinkServer.java` (WS port `8765`, HTTP proxy port `8766`), `CinemaHlsActivity.java`, `CinemaLocalMediaActivity.java`
  - Remote & Input Bridges: `TvLocalAdbBridge.java`, `TvRemoteAccessibilityService.java`, `TvRemoteImeService.java`, `TvRemotePermissionKeeper.java`
  - Subtitles & Storage: `CinemaAutoSubtitles.java`, `SubtitleMatch.java`, `SubsourceSettingsActivity.java`, `SubsourceSearchActivity.java`, `ApiKeyStore.java`
- **Legacy / Native Video Player (`Video/src/main/java/com/archos/mediacenter/video/player/`):**
  - `PlayerActivity.java`, `PlayerService.java`, `PlayerController.java`, `SurfaceController.java`, `CinemaSecondarySubtitle.java`, `CinemaSubtitleAppearance.java`
- **Shared Core Libraries:** `../MediaLib/`, `../FileCoreLibrary/`, `../shared-providers/`, `../shared-updater/`.

## Build and test

Follow [the monorepo release procedure](../docs/RELEASE.md) for signed builds and both ADB targets. Use `assembleNoamazonRelease` with the pinned native libraries. The staging script selects the armeabi-v7a APK.

## TV Guardrails

- Add every new user-facing label, button, title, message, dialog and accessibility description to both English `values/strings.xml` and Arabic `values-ar/strings.xml` in the owning app. Use resource IDs in code and preserve RTL/LTR behavior.
- **Positive `900_000_000L+` Synthetic MediaDB ID Rule:** All virtual/remote `trakt://` `Video`/`Movie`/`Episode` objects built for Home or Watchlist UI (`CinemaHomeFragment.java`, `CinemaWatchlistActivity.java`) MUST assign positive `900_000_000L+` IDs (`Video.<init>` asserts non-negative IDs and crashes on launch otherwise).
- Preserve `AspectRatioFrameLayout` (`RESIZE_MODE_FIT`) 4K/portrait/rotation handling and persisted dual subtitle position (`pref_dual_sub_position`) in `CinemaHlsActivity`, `CinemaLinkServer`, `CinemaSecondarySubtitle`, and `PlayerActivity`.
- Preserve `applicationId = "com.meedx.localcinema.tv"` and `MediaLib` provider authorities for seamless in-place upgrades.
