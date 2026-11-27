# Local Cinema TV (`Video/`)

Start with [root AGENTS.md](../AGENTS.md) and [HANDOFF.md](../HANDOFF.md). Gradle root: `Video/`. Package ID: `com.meedx.localcinema.tv`.
Base Java path: `Video/src/main/java/com/archos/mediacenter/video/`.

## Functional TV file map

### 1. Playback Engine & Nova Quarantine Boundary (`leanback/cinema/` & `player/`) — *Engine v2 Ready*

- **Engine Routing & Boundaries (`leanback/cinema/`):**
  - `CinemaPlaybackRouter.java` — Single entry point for launching local/SMB movies, season episodes, VOD/addon/torrent streams, and Live TV channels + canonical bookmark constants (`BOOKMARK_COMPLETED`, `BOOKMARK_UNKNOWN`).
  - `CinemaEngineBoundary.java` — Isolates HDMI display mode/refresh-rate matching (`cinema_display_mode`, `cinema_display_delay_ms`), HDR/RAM hardware profiling, aspect constants, and `VideoMetadata` probing.
  - `CinemaNovaLegacyPrefs.java` — Quarantines legacy Nova `SharedPreferences` keys (`ui_lang`, `enable_dynamic_audio_delay`, `playback_speed`, `pref_smbj`, `PlayerActivity.KEY_SUBTITLE_*`, `pref_dual_sub_position`) and legacy Nova preference/list screens away from modern Cinema UI.
  - `CinemaPlaybackOption.java` & `CinemaPlaybackFilters.java` — Family Viewing (`CleanStream` / `MovieHavenDB` / `.mcf` / caption cues) and Skip Ads (`.edl` / chapters) engine.
  - `CinemaTrackChoices.java` & `CinemaEmbeddedChapters.java` — Per-media remembered audio/embedded-subtitle track indices and MKV/MP4 chapter parsing.
- **Stream, Torrent & Cast Players (`leanback/cinema/`):**
  - `CinemaHlsActivity.java` — ExoPlayer / Media3 VOD, HLS/DASH, Stremio add-on, torrent, and Live TV player with custom HTTP headers, rotation-safe `AspectRatioFrameLayout`, live subtitle hub, and failover sources.
  - `CinemaTorrentStream.java` & `CinemaTorrentHttpServer.java` — Sequential `libtorrent4j` piece streaming and local HTTP range server for magnet/torrent playback.
  - `CinemaLocalMediaActivity.java` & `AudioReactiveCanvasView.java` — Phone-cast audio/photo receiver with `MediaSession` and reactive audio visualizer.
- **Native AVOS/FFmpeg Player (`player/`):**
  - `PlayerActivity.java`, `PlayerService.java`, `Player.java`, `PlayerController.java`, `SurfaceController.java` — Legacy native video player, playback lifecycle, OSD, and surface aspect controller (Info action routes to `CinemaMediaMoreDialog`).
  - `CinemaDisplayModes.java` & `HardwareCapabilityProfiler.java` — Refresh-rate/resolution HDMI mode matcher and Dolby Vision / HDR10+ / RAM capability detector (accessed via `CinemaEngineBoundary`).
  - `CinemaDpadSeek.java` — Accelerated D-pad scrubbing helper shared across players.
  - `SubtitleManager.java`, `SubtitleTextView.java`, `Subtitle3DTextView.java`, `SubtitleGfxView.java`, `CinemaSecondarySubtitle.java`, `CinemaSubtitleAppearance.java`, `CinemaSubtitleText.java`, `AdaptiveSubtitleContrast.java` — Primary/dual subtitle rendering and tight per-line rounded background boxes.

### 2. Home, Library, Details & Unified More Sheet (`leanback/cinema/`)

- `CinemaHomeFragment.java` & `CinemaHomeLoader.java` — Main TV home screen, hero backdrop/logo banner, Continue Watching, indexed folder rows (`lib:<FolderName>`), and `CursorLoader` queries.
- `CinemaContextDialog.java` — Unified `#171717` / `#E5A93B` D-pad modal menu for poster long-press options and row `Sort By` (vector icons, active badge, Back-to-close).
- `HomePreferences.java` — Home row visibility, priority ordering, poster grid density, backdrop quality, and clock/weather toggles.
- `CinemaNavigation.java`, `CinemaActionMenu.java`, `CinemaActionChip.java` — Collapsible RTL/LTR left/right navigation rail, docked sidebar sub-panels (`Libraries`, `Search`), and Plex-style icon action chips.
- `CinemaDetailsActivity.java` — Movie & single-episode details screen, multi-ratings, cast strip, audio/subtitle pickers, and trailer launcher.
- `CinemaSeriesActivity.java` — Series & multi-season browser, season pills, episode progress cards, and binge playback.
- `CinemaMediaMoreDialog.java` — Unified `#171717` / `#E5A93B` «More / Technical Details & Media Actions» modal replacing Nova's legacy `VideoInfoActivity` / `VideoDetailsActivity` / `TvshowActivity` across Details, Series, Home, Watchlist, and `PlayerActivity`.
- `CinemaWatchlistActivity.java`, `CinemaWatchStore.java`, `CinemaMediaTime.java` — Watchlist & Watched poster grids, local/Phone/Trakt watch-state DB & sync store, and in-memory/DB resume position tracker.
- `CinemaActorActivity.java`, `CinemaTrailersActivity.java`, `CinemaYouTubeLauncher.java`, `CinemaDiscover.java` — Cast filmography & add-on stream lookup, trailers row/screen, SmartTube/YouTube launcher, and curated discovery rows.
- `CinemaWeatherWidget.java`, `CinemaUi.java`, `CinemaTokens.java` — Home clock/weather widget, shared `#171717`/`#E5A93B` D-pad UI primitives, and design tokens.

### 3. Posters, Artwork & Multi-Ratings (`leanback/cinema/` & `com/meedx/providers/`)

- `CinemaArtwork.java`, `CinemaPosters.java`, `CinemaMetadataCache.java`, `CinemaMetadataPrefetcher.java`, `MetadataLanguage.java` — Bilingual AR/EN poster/backdrop/logo resolution, disk/memory caching, background prefetching, and manual poster chooser.
- `CinemaRatingBadge.java`, `CinemaRatingsActivity.java`, `CinemaImdbRatings.java`, `CinemaOmdbRatings.java` — Multi-rating badge renderer (IMDb, TMDb, RT, Metacritic), offline IMDb dataset importer, and OMDb client.
- `Video/src/main/java/com/meedx/providers/OpenMetadata.java` — Keyless open metadata/artwork/cast lookup and TMDb bridge.

### 4. Network Hub (`SMB` / `UPnP` / Indexed Sources) & File Browser (`leanback/cinema/`)

- `CinemaNetworkActivity.java` — Modern `#171717` / `#E5A93B` Network & Library Sources screen replacing Nova's `NetworkRootActivity`, with in-place SMB/UPnP/WebDAV folder browsing, dark/gold server credentials dialog, auto-scan dialog, and one-tap folder indexing.
- `CinemaNetworkController.java` — Isolated network controller encapsulating `SambaDiscovery` (SMB), `UpnpServiceManager` (DLNA/UPnP), `ShortcutDbAdapter.VIDEO` (indexed folders), `ShortcutDb.STATIC` (saved servers), `NetworkCredentialsDatabase`, and async `RawListerFactoryWithUpnp` listing.
- `CinemaLibraryScan.java` & `NetworkUtils.java` — Smart SMB host reachability watcher, media scanner trigger, playback filter warmer, and LAN IPv4 resolver.
- `CinemaFileBrowserActivity.java` & `CinemaFileOperations.java` — Unified local/USB/Art-Gallery/subtitle file browser (reuse for all local/subtitle/image picking) with multi-select copy/move/delete and SAF/MediaStore handling.

### 5. Unified Subtitles & Preferred Audio (`leanback/cinema/`)

- `CinemaSubtitleHubDialog.java`, `CinemaSubtitleAppearancePanel.java`, `CinemaSubtitleColorPickerDialog.java`, `CinemaSubtitleTimingOverlay.java` — Unified live & pre-play Subtitle Hub, live appearance/opacity/outline controls, 9-color text / 5-color outline dialog, and translucent live timing offset bar.
- `CinemaSubtitleSyncBridge.java` — Syncs `UnifiedSubtitleConfig` with legacy TV prefs (`CinemaNovaLegacyPrefs`) and broadcasts changes to paired Phones.
- `CinemaAutoSubtitleFetcher.java`, `CinemaAutoSubtitles.java`, `SubtitleSources.java`, `SubtitleStorage.java`, `CinemaSubtitleLabels.java`, `CinemaSubtitleCodec.java` — One-tap auto-fetch, background matcher, seed builder, persistent video-to-subtitle binding DB, Arabic/RTL label formatter, and charset/encoding normalizer.
- `SubsourceSearchActivity.java`, `SubsourceSettingsActivity.java`, `SubsourceClient.java`, `OpenSubtitleSearch.java` — Smart interactive subtitle search UI, Unified Subtitle Settings screen, Subsource API client, and OpenSubtitles client.
- `CinemaLanguagePreferences.java` & `CinemaPreferredAudio.java` — Primary/secondary subtitle language helpers and preferred audio track auto-selector.

### 6. Stremio Add-ons, Live TV & Ambient Art Mode (`leanback/cinema/`)

- `CinemaAddonsActivity.java`, `CinemaAddonsSectionController.java`, `CinemaAddonTilePresenter.java`, `CinemaAddonsManagerDialog.java`, `CinemaAddonsOrderStore.java`, `CinemaAddonPicker.java` — Stremio add-ons catalog hub, category rows, unified add-on manager with logos, ordering store, and movie/episode stream picker.
- `CinemaLiveActivity.java`, `CinemaLivePlaylists.java`, `CinemaLiveCategories.java` — Live TV / IPTV player grid, M3U/playlist parser, country/category filters, and channel favorites.
- `CinemaAmbientLauncherDialog.java`, `CinemaArtModeActivity.java`, `CinemaArtFrameView.java`, `CinemaArtSettingsSheet.java`, `CinemaArtStorage.java`, `CinemaAmbientYoutubeActivity.java`, `AmbientYoutubeFetcher.java`, `AmbientYoutubeSettingsDialog.java` — Art Gallery frame mode and 4K YouTube ambient screensaver.

### 7. Settings, Cache Cleaner, Phone Link & Remote Services (`leanback/cinema/`)

- `CinemaSettingsActivity.java` & `CinemaCacheMaintenance.java` — Grouped `#171717`/`#E5A93B` TV Settings hub (with strict language deduplication: App & Poster languages in `Language & Priorities`, Audio priority exclusively in `Playback, Audio & Subtitles`, and Preferred Subtitle Language exclusively in `SubsourceSettingsActivity`) and safe cache analyzer/self-maintenance cleaner.
- `CinemaLinkServer.java` (WS `8765`, HTTP `8766`), `CinemaPhoneLink.java`, `CinemaPhoneLinkActivity.java`, `CinemaTextImportActivity.java` — Bidirectional Phone WebSocket/HTTP server (remote, cast, Trakt/Add-on/Subtitle/Backup sync, deterministic `tv_settings_updated_at` + `tv_settings_hash` tracking across all API keys/OMDb/Stremio add-on configs & order/SMB shares & credentials/Home rows/Family Watch, and instant `TV_SETTINGS_CHANGED` push to connected Phones), QR pairing UI, and text paste receiver.
- `TvPermissionsWizardDialog.java`, `TvLocalAdbBridge.java`, `TvRemoteAccessibilityService.java`, `TvRemoteImeService.java`, `TvRemotePermissionKeeper.java`, `TvBackgroundConnectionService.java`, `TvBootReceiver.java` — Onboarding permissions wizard, hybrid Local ADB + Accessibility + IME remote input services, TCL/OEM background sleep guard, and boot starter.

### 8. Quarantined Legacy Nova Packages (Do Not Expand)

- `leanback/network/*` (superseded by `CinemaNetworkActivity`; `NetworkRootActivity` redirects automatically), `leanback/details/*` & `info/*` (superseded by `CinemaDetailsActivity` & `CinemaMediaMoreDialog`), `leanback/tvshow/*` (superseded by `CinemaSeriesActivity`), `leanback/settings/*` & `utils/VideoPreferences*` (quarantined behind `CinemaNovaLegacyPrefs`), `leanback/search/*` (`VideoSearchActivity` / `VideoSearchFragment` — clicks route via `VideoViewClickedListener` to `CinemaDetailsActivity` / `CinemaSeriesActivity`).

## Build & TV guardrails

- **Build:** Follow [docs/RELEASE.md](../docs/RELEASE.md) sequentially (`assembleNoamazonRelease -x :FileCoreLibrary:ndkBuild -x :MediaLib:ndkBuild --no-daemon --max-workers=2`; staging selects `armeabi-v7a`).
- **Visual Identity & Zero-Clipping:** Soft Black `#171717`, Gold `#E5A93B`, White `#FFFFFF`. Keep `clipChildren="false"`, `clipToPadding="false"`, and `>= 10dp` padding on focusable rows/cards; wrap scrolling grids/lists in a clipped viewport (`clipChildren="true"`, `clipToPadding="true"`). Full `AR RTL` / `EN LTR` support.
- **Preservation:** Keep positive `900_000_000L+` synthetic IDs, `AspectRatioFrameLayout` (`RESIZE_MODE_FIT`) rotation handling, and `pref_dual_sub_position`.
