# Local Cinema Agent Guide

Local Cinema TV uses `com.meedx.localcinema.tv` (`Video/`). Local Cinema Manager uses `com.meedx.localcinema.manager` (`Phone/`). Both apps share `shared-providers/` and `shared-updater/`.

## Current state

- **Official Repository & Releases:** `alnouri96/local-cinema` (default branch: `main`).
- **Official OTA Feed:** `https://meedx.pages.dev/api/v2/version.json` (allowlist strictly limited to `github.com/alnouri96/local-cinema` and `meedx.pages.dev`).
- **Published Latest:** `v1.0.0` (`versionCode = 20`, 2026-11-27) - first stable release.
- Active work and status: see [HANDOFF.md](HANDOFF.md). Build and release procedure: see [docs/RELEASE.md](docs/RELEASE.md).
- Release history lives exclusively in [CHANGELOG-CINEMA.md](CHANGELOG-CINEMA.md) and `releases/<tag>/checksums.txt`.

## Module routing & functional codebase map

- **TV App (`Video/`):** [Video/AGENTS.md](Video/AGENTS.md) — Android TV UI, playback boundary, network hub, unified subtitles, Stremio add-ons, Live TV, Art Mode, and TV Phone-Link server.
- **Phone App (`Phone/`):** [Phone/AGENTS.md](Phone/AGENTS.md) — Companion TV Hub, floating remote, GeckoView media/magnet browser, subtitle/Trakt/Drive sync, and torrent/casting engine.
- **Website & OTA (`G:/MEEDX WEB`):** sibling `MEEDX WEB/AGENTS.md` (`https://meedx.pages.dev`).

### `shared-providers/src/com/meedx/providers/` (Cross-app providers & sync models)

- **Unified Subtitles:**
  - `UnifiedSubtitleConfig.java` — Central subtitle preferences, language priority list, live appearance (`size`, `vpos`, `color`, `bgOpacity`, `outline`), `pref_dual_sub_position`, and dedicated Stremio subtitle add-on sync JSON.
  - `UnifiedSubtitleEngine.java` — Parallel subtitle search across Subsource, OpenSubtitles, zero-config REST, and dedicated Stremio subtitle add-ons (`USS`, `SubSense`).
  - `SubtitleMatch.java` — Pure-Java release/FPS/hash/season-episode subtitle ranking and scoring.
  - `ZeroConfigSubtitles.java` — Keyless OpenSubtitles REST/hash fallback lookup.
  - `SubtitleArabicTranslator.java` — On-demand translation of subtitle cues into Arabic.
- **Add-ons, Metadata & Ratings:**
  - `StremioAddons.java` — Stremio v3 manifest/catalog/meta/stream/subtitles client, curated presets, `kitsu:`/`tmdb:`/`tt` ID bridging, cross-protocol redirects, and `proxyHeaders` forwarding.
  - `MultiRatingsProvider.java` — Aggregated IMDb, TMDb, Rotten Tomatoes, and Metacritic ratings resolver.
  - `OpenTrailersProvider.java` — Official trailer lookup across TMDb and YouTube.
- **Trakt, Keys & Live TV:**
  - `TraktSyncClient.java` — Trakt OAuth 2.0 `S256` PKCE authentication, automatic token refresh, and history/progress/watchlist sync.
  - `ProviderKeys.java` & `ApiKeyStore.java` — Runtime obfuscated/prefs API key storage (`TMDB`, `OMDB`, `SUBSOURCE`, `OPEN_SUBTITLES`) without hardcoded secrets.
  - `LiveTvPreferences.java` & `LiveChannelFavorites.java` — IPTV country filters, playlist preferences, and synced favorite channels.

### `shared-updater/src/com/meedx/` (OTA updater & key editor)

- `updates/AppUpdates.java` — In-app OTA update check, release notes dialog, background APK download, SHA-256 verification, and installer trigger.
- `updates/UpdateConfig.java` — Strict domain allowlist (`meedx.pages.dev`, `github.com/alnouri96/local-cinema`) and feed endpoints.
- `updates/ReleaseSigner.java` — Signing certificate SHA-256 continuity verifier prior to package install.
- `updates/UpdateFileProvider.java` — Dedicated `FileProvider` for staging OTA APKs safely.
- `updates/ProjectLinks.java` — Official GitHub source and `THIRD_PARTY.md` license intents.
- `providers/ProviderSettings.java` — Shared API-key input dialog with TV Phone-Paste hook.

### `MediaLib/` & `FileCoreLibrary/` (TV underlying media DB, scraper & virtual FS)

- **`MediaLib/src/com/archos/`:**
  - `medialib/` — Native AVOS/FFmpeg & Android `IMediaPlayer` bridges and container track probing.
  - `mediaprovider/` & `mediaprovider/video/` — `VideoProvider`, `VideoStore` SQLite schema, `NetworkScannerServiceVideo` SMB/folder indexer, `NetworkAutoRefresh`, and `RemoteStateService`.
  - `mediascraper/` & `themoviedb3/` — `BaseTags`/`MovieTags`/`EpisodeTags`, filename `preprocess` parsers, TMDb v3 scraper, and NFO/XML handlers.
  - `mediacenter/utils/` & `filecoreextension/upnp2/` — `ShortcutDbAdapter` (indexed library roots), `videodb/IndexHelper`, legacy `trakt`, and jUPnP/DLNA `UpnpServiceManager` + `RawListerFactoryWithUpnp`.
- **`FileCoreLibrary/src/com/archos/`:**
  - `filecorelibrary/` — Virtual file abstraction (`MetaFile2`, `RawLister`, `FileEditor`, `ListingEngine`, `StreamOverHttp` local HTTP proxy).
  - Protocol transports: `samba/` & `jcifs/` (JCIFS-NG SMBv1/v2, LAN `SambaDiscovery`, `NetworkCredentialsDatabase`), `smbj/`, `ftp/`, `sftp/`, `sshj/`, `webdav/`, `localstorage/` & `contentstorage/` (`ExtStorageManager` / SAF), `zip/`, and `environment/`.

### `script/` & `docs/` (Build, security audit & release tooling)

- `script/build-both.ps1` & `script/build-cinema.ps1` — Sequential TV + Phone release build wrappers.
- `script/stage-clean-release.ps1` — Verifies package IDs, ABIs (`armeabi-v7a` TV, `arm64-v8a` Phone), cert continuity, and key audit; stages into `releases/_local/` or `releases/v<ver>/`.
- `script/audit-provider-keys.py` — Scans Release APKs (`PASS` required) to ensure zero leaked API keys or secrets.
- `script/tv-capture.py` — Optional ADB frame capture helper when explicitly requested.
- `docs/RELEASE.md` (release runbook), `docs/THIRD_PARTY.md` (attributions), `docs/PROVIDER-KEYS-GUIDE.md`, `docs/SETUP-AR.md`.

## Permanent engineering rules

1. **Modular & Isolated Code:** Build every new feature, screen, or sheet in its own focused class/file (never stack code into large coordinators like `CinemaHomeFragment.java` or `TvHubActivity.java`). Register every new file immediately in the owning module's `AGENTS.md` map while keeping each `AGENTS.md` under **240 lines**.
2. **No Duplication / Reuse Existing Features:** Reuse existing pickers, dialogs, shared providers, and boundaries (`CinemaPlaybackRouter`, `CinemaEngineBoundary`, `CinemaNovaLegacyPrefs`, `CinemaMediaMoreDialog`, `CinemaNetworkActivity`, `CinemaFileBrowserActivity`, `UnifiedSubtitleConfig`).
3. **Sequential Gradle Builds Only:** Run Gradle commands one at a time with `--no-daemon --max-workers=2`.
4. **Targeted Read Policy:** Read only this file, `HANDOFF.md`, and the target module's `AGENTS.md`, then jump straight to the mapped source files. Never generate external graph artifacts (e.g., `Graphify`).
5. **Mandatory Handoff Protocol (`HANDOFF.md`):**
   - `HANDOFF.md` tracks the live unreleased patch checkpoint only (Empty = published clean; Populated = active checkpoint).
   - Update `HANDOFF.md` **in place** after every edit/stage (consolidated bullets, latest `releases/_local/` path + SHA-256 hashes, next step) without duplicating entries.
   - Upon official release publication, move notes to `CHANGELOG-CINEMA.md` and reset `Active Unreleased Patch` to empty.

## Preservation & security guardrails

1. **Trakt OAuth 2.0 PKCE Only:** Public `client_id` `ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE`, redirect `localcinema://trakt-callback`, `S256` PKCE. **Never add `client_secret`**.
2. **Manual / On-Change Sync Policy:** Full Phone-to-TV and Trakt syncs run only on explicit user action or setting/key change.
3. **Positive TV Synthetic MediaDB IDs (`900_000_000L+`):** Virtual/remote `Video`/`Episode` objects on TV must use positive `900_000_000L+` IDs.
4. **Provider-Key Security Audit (`PASS`):** `python script/audit-provider-keys.py` must return `PASS` on both Release APKs before staging/install.
5. **Architecture, Ports & Bilingual Stability:** Preserve `applicationId`s, `MediaLib` authorities, signing certs, `pref_dual_sub_position`, ports (`8765` WS, `8766` HTTP media, `8080` WebDAV library), and full `AR RTL` / `EN LTR` support.
6. **Visual Identity:** Soft Black `#171717`, Gold `#E5A93B`, White `#FFFFFF`, unclipped focus rings (`>= 10dp` padding, `clipChildren="false"`), clipped scroll viewports (`clipChildren="true"`).
7. **Approval Gate:** Never `git push`, create a GitHub Release, or deploy `MEEDX WEB` without explicit developer approval.
