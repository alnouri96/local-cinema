# Local Cinema TV

Start with [root AGENTS.md](../AGENTS.md). Gradle root: `Video/`. Package ID: `com.meedx.localcinema.tv`.

## TV file map

Base package: `Video/src/main/java/com/archos/mediacenter/video/`.

- **Home, Library & Details (`leanback/cinema/`):** `CinemaHomeFragment.java`, `CinemaHomeLoader.java`, `CinemaDetailsActivity.java`, `CinemaSeriesActivity.java`, `CinemaWatchlistActivity.java`, `CinemaWatchStore.java`, `CinemaActorActivity.java`, `CinemaRatingsActivity.java`, `CinemaTrailersActivity.java`, `CinemaDiscover.java`, `CinemaActionChip.java`, `CinemaActionMenu.java`, `CinemaRatingBadge.java`, `CinemaWeatherWidget.java`, `CinemaUi.java`, `CinemaTokens.java`, `HomePreferences.java`
- **Add-ons Hub & Catalogs (`leanback/cinema/`):** `CinemaAddonsActivity.java`, `CinemaAddonsSectionController.java`, `CinemaAddonTilePresenter.java`, `CinemaAddonsManagerDialog.java`, `CinemaAddonsOrderStore.java`, `CinemaAddonPicker.java`
- **Unified File Browser (`leanback/cinema/`):** `CinemaFileBrowserActivity.java` (single browser for videos, photos, and subtitles — reuse for all TV file picking), `CinemaFileOperations.java`
- **Art Mode & YouTube 4K Screensaver (`leanback/cinema/`):** `CinemaAmbientLauncherDialog.java`, `CinemaArtModeActivity.java`, `CinemaArtFrameView.java`, `CinemaArtSettingsSheet.java`, `CinemaArtStorage.java`, `CinemaAmbientYoutubeActivity.java`, `AmbientYoutubeFetcher.java`, `AmbientYoutubeSettingsDialog.java`, `CinemaYouTubeLauncher.java`
- **Live TV (`leanback/cinema/`):** `CinemaLiveActivity.java`, `CinemaLivePlaylists.java`, `CinemaLiveCategories.java`
- **Player, Cast Receiver & Torrent Engine (`leanback/cinema/` & `player/`):** `CinemaLinkServer.java` (WS `8765`, HTTP `8766`), `CinemaLocalMediaActivity.java` (phone-cast audio/photo player & `MediaSession`), `CinemaHlsActivity.java`, `CinemaTorrentStream.java`, `CinemaTorrentHttpServer.java`, `CinemaPlaybackOption.java`, `CinemaPlaybackFilters.java`, `CinemaTrackChoices.java`, `CinemaEmbeddedChapters.java`, `AudioReactiveCanvasView.java`; native player in `player/`: `PlayerActivity.java`, `PlayerService.java`, `PlayerController.java`, `SurfaceController.java`, `CinemaDpadSeek.java`, `CinemaDisplayModes.java`, `CinemaSecondarySubtitle.java`, `CinemaSubtitleAppearance.java`, `CinemaSubtitleText.java`
- **Subtitles, Languages, Remote & Onboarding (`leanback/cinema/`):** `CinemaLanguagePreferences.java`, `CinemaPreferredAudio.java`, `CinemaAutoSubtitles.java`, `CinemaSubtitleLabels.java`, `CinemaSubtitleSyncBridge.java`, `CinemaSubtitleHubDialog.java`, `CinemaSubtitleTimingOverlay.java`, `CinemaAutoSubtitleFetcher.java`, `SubtitleSources.java`, `SubtitleStorage.java`, `CinemaSubtitleCodec.java`, `SubsourceSearchActivity.java`, `SubsourceSettingsActivity.java`, `SubsourceClient.java`, `OpenSubtitleSearch.java`, `CinemaSettingsActivity.java`, `CinemaCacheMaintenance.java`, `TvPermissionsWizardDialog.java`, `TvLocalAdbBridge.java`, `TvRemoteAccessibilityService.java`, `TvRemoteImeService.java`, `TvRemotePermissionKeeper.java`, `TvBackgroundConnectionService.java`, `TvBootReceiver.java`
- **Resources & Shared:** `Video/res/values/strings.xml`, `Video/res/values-ar/strings.xml`, `Video/res/drawable-nodpi/ic_art_gallery_*`, `../MediaLib/`, `../FileCoreLibrary/`, `../shared-providers/`, `../shared-updater/`.

## Build

Follow [docs/RELEASE.md](../docs/RELEASE.md). Build with `assembleNoamazonRelease` using pinned native libs (staging selects `armeabi-v7a`).

## TV guardrails

- **Visual Identity & Zero-Clipping:** Use Soft Black `#171717`, Gold `#E5A93B`, White `#FFFFFF`. Keep `clipChildren="false"`, `clipToPadding="false"`, and `>= 10dp` padding on focusable rows/cards so gold rings and captions never clip; wrap scrolling `RecyclerView` grids in a clipped `viewport` (`clipChildren="true"`, `clipToPadding="true"`) so scrolled items never overlap headers. Verify UI changes on the real TV screen.
- **Modularity & Reuse:** Keep new TV features in separate classes under `leanback/cinema/` and add them to this map immediately; route all local video/photo/subtitle browsing through `CinemaFileBrowserActivity`.
- **Playback & IDs:** Preserve positive `900_000_000L+` synthetic IDs in `CinemaHomeFragment.java` and `CinemaWatchlistActivity.java`, `AspectRatioFrameLayout` (`RESIZE_MODE_FIT`) rotation handling, and `pref_dual_sub_position` across `CinemaHlsActivity`, `CinemaLinkServer`, `CinemaSecondarySubtitle`, and `PlayerActivity`.
