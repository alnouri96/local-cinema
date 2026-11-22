# NOVA Cinema component blueprint

## Overview

This package provides an Android TV home, film details, settings, a watchlist,
and Arabic subtitle workflows on top of the existing NOVA media provider and
playback engine. It does not implement a separate media catalogue or player.

## Interface

| Boundary | Public entry points | Existing dependency |
| --- | --- | --- |
| Home | `CinemaHomeFragment`, `CinemaHomeLoader` | `VideoLoader`, `AllTvshowsLoader`, `VideoCursorMapper`, `TvshowCursorMapper` |
| Details | `CinemaDetailsActivity` | `VideoDetailsFragment.EXTRA_VIDEO`, `PlayUtils.startVideo`, `DbUtils` |
| Navigation | `CinemaSettingsActivity`, `CinemaWatchlistActivity` | NOVA network, settings, search and show activities |
| Presentation | `CinemaUi`, `CinemaTokens`, `HomePreferences`, `CinemaArtwork.load` | Android views, Picasso, Palette, NOVA's TMDB source |
| Key management | `ApiKeyStore.read/save/parse/masked`, `SubsourceSettingsActivity` | Android Keystore, SAF, non-stub GET_CONTENT chooser, or `CinemaTextImportActivity` |
| API | `SubsourceClient.json/download/test/parseRetryAfter` | Official Subsource v1 HTTPS API |
| Matching | `SubtitleMatch.rank` | Pure Java, no Android or network dependency |
| Subtitle persistence | `SubtitleStorage.lookup/bind/save/unpack` | SQLite `subtitle_binding`, FileCoreLibrary editors, `SubtitleManager` |
| Subtitle selection | `SubsourceSearchActivity`, `CinemaSubtitleBrowserActivity` | Original file/SMB listers and NOVA player service |

## Invariants

- `CinemaHomeLoader.getSelection/getSortOrder` queries the real NOVA provider,
  with a maximum of 50 items per home row. Empty rows are hidden by
  `CinemaHomeFragment.updateVisibility`; visible rows follow `HomePreferences.order`.
- `HomePreferences.order` returns each valid ID 1–4 exactly once. Unknown,
  duplicated and missing persisted IDs cannot discard a required row.
- `CinemaHomeFragment.onCreateView` keeps the navigation rail physically left.
  Clock formatting uses `Locale.US`, with system time and time zone.
- Database queries, source listing, subtitle requests, archive extraction and
  artwork enrichment run through loaders or worker executors. Play does not
  wait for optional artwork enrichment.
- `SubsourceSearchActivity.confirm` requires a Download action for the selected
  result. Multiple archive entries require a further file choice.
- `SubtitleMatch.rank` derives missing episode identity from scene filenames,
  rejects explicit episode conflicts, penalizes year conflicts and caps high
  confidence when timing evidence conflicts. A title alone cannot prove an edition.
- `SubsourceClient.request` sends the API key only to `api.subsource.net`, does
  not follow redirects, bounds responses and never logs headers or bodies.
  HTTP 429 persists a retry deadline; backup keys are selected manually.
- `ApiKeyStore` uses Android Keystore AES-GCM and private encrypted preferences.
  `parse` removes BOM/whitespace and deduplicates tokens. UI previews are masked.
- `SubtitleStorage.unpack` supports SRT/ASS/SSA/VTT, bounds archive entry count,
  per-entry bytes and total expanded bytes, including rejected paths. It does
  not extract archive paths onto the filesystem.
- `SubtitleStorage.save` preserves existing subtitle targets. It binds a private
  copy when adjacent file/SMB storage is unavailable. The original video is never
  a write target.
- Adjacent writes use `FileEditor.getOutputStreamExclusive`: local exclusive
  creation, JCIFS `O_CREAT | O_EXCL`, or SMBJ `FILE_CREATE`. Other protocols fail
  closed. A failed write attempts to delete only its newly created partial file.
- `CinemaSubtitleBrowserActivity` binds the selected file without renaming it.
  Its generation token prevents stale directory callbacks replacing a newer view.
- Activities cancel pending UI callbacks and stop their workers on destruction.
  Home resets view-bound state during view recreation and cancels recycled image
  requests before rebinding them.

## Side Effects

- The home reads the original provider and source shortcut database. Confirmed
  source removal deletes a shortcut and requests catalogue removal through
  `NetworkScanner.removeVideos`; it never invokes file deletion.
- Details delegate playback/resume/restart to NOVA, update watched state through
  `DbUtils`, and persist the Cinema watchlist by media path.
- Appearance settings persist row visibility/order, poster density/size, clock,
  motion, backdrop quality and language. Advanced settings remain reachable.
- `CinemaArtwork.load` caches TMDB responses in app cache, prefers English
  logos/posters, and supplies cast portraits and official trailers when available.
- `SubsourceClient.reserve` persists conservative request counters and retry
  state. Its bounded, per-key JSON cache remains in memory.
- `SubtitleStorage` writes subtitle files and its own SQLite bindings, updates
  selection hints, and invalidates the original subtitle cache. The player service
  reloads external subtitles and respects subsequent manual track choices.

## Verification

From the repository root on Windows:

```powershell
./script/build-cinema.ps1
javac -encoding UTF-8 -d .build-cache/test-classes Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/SubtitleMatch.java script/SubtitleMatchChecks.java
java -cp .build-cache/test-classes SubtitleMatchChecks
adb -s 192.168.100.8:5555 shell am instrument -w -e command checks org.nova.cinema/com.archos.mediacenter.video.leanback.cinema.CinemaSmokeInstrumentation
```

`CinemaSmokeInstrumentation` exists only in the debug source set. Real playback,
SMB, focus navigation and import workflows require device testing. See the root
test report for measured results and limitations; a successful build is not a
claim of codec or device compatibility.

## Supplemental visual revision

`DESIGN.md` at the repository root describes the native TV token roles.
`CinemaNavigation.attach` supplies detail-screen navigation; the home still owns
actual source actions. English is the current default, with Arabic selectable.
`CinemaDetailsActivity` separates sourced TMDB ratings from file-probed technical
capsules, caches successful probe results for the session, and labels focused
secondary actions. No filename-derived HDR/audio capability is presented as a
measurement. Upstream retained screens share dark theme aliases while retaining
their existing native controls and layouts.

## Provider contract revision

`CinemaMetadataCache.get(context, typedPath, language)` is worker-only, atomic,
24h/128-file bounded, with 30-day stale-on-error. `CinemaArtwork.Art` adds year,
runtime, certification and IMDb identity. `loadEpisode` keys show + season +
episode and retains actual episode title, date, director and guests. Artwork
language preference is independent of text language. Existing HTTP/image and
scraper persistence are reused.

`CinemaImdbRatings` manually downloads the official personal-use dataset into
private files, validates gzip/header/size, then resolves exact ids on workers;
selected lookups persist under cache with dataset-version invalidation.
`CinemaOmdbRatings` is optional, HTTPS-only, independently encrypted and cached
per key/id for 24h. It validates matching identity and explicit bounded RT critics
percentages; it never invents an audience score. `CinemaRatingBadge` labels each
source. `CinemaRatingsActivity` exposes setup, attribution and provider terms.
`ApiKeyStore(context)` retains original Subsource alias/preferences; the explicit
`omdb` namespace is separate. No user keys are included in source or APK assets.

MediaLib `ProviderIdentity` and `ExplicitProviderLookup` run before legacy fuzzy
matching; invalid/conflicting explicit ids fail closed. Show results retain
isMovie=false and original season/episode. No engine or SMB change is involved.
See root METADATA_PROVIDERS.md and docs/archive/GOLD-METADATA-TESTS.md for evidence.

## User revision v5 contract

`CinemaMediaTime` keeps measured milliseconds separate from provider minutes and
formats h/m/s; native inspection updates the detail metadata display. Resolved
title visibility is deferred until enrichment or an explicit fallback. Cast focus
outlines the portrait rather than the label container. `CinemaUi.navigationLabel`
centers collapsed icons and restores expanded text padding; numbers display 0–9.
Home appends actual manual file-selection actions below provider rows, reuses
poster geometry and encodes the vertical focus edges. A non-stub GET_CONTENT
video chooser grants access to the selected URI; native file browsing is the
fallback. No automatic library addition or deletion occurs.
Subsource searches on entry, bypasses title selection only for unique verified
IMDb/season identity, and keeps rank ordering and download confirmation. Original
NOVA settings links are consolidated in System. Device evidence is UI-V5-TESTS.md.

## User revision v6 contract

`CinemaTrackChoices` stores independent per-file audio indices and embedded
subtitle ordinals (-2 automatic / -1 off). `PlayerService` validates choices
against actual metadata before applying them through the existing player API;
normal in-player audio changes update the preference. Original in-player manual
subtitle changes and `SubtitleStorage.bind` clear the embedded override. Details
metadata retrieval does not advertise decoder support from a probe-only flag.
The dropdown restores its anchor focus on horizontal cancellation. The old
subtitle-count capsule is removed; native in-player menus remain intact.

`CinemaSeriesActivity` reads `EpisodesLoader` by original show database id,
groups actual seasons, caches still metadata by provider id/season, and opens
`CinemaDetailsActivity` for a real Episode. The original TvshowActivity redirects
normal serialized show entry points; advanced legacy code remains available.
The next/resume action delegates to PlayUtils, while provider reloads refresh
resume state and retain the selected episode. See docs/UI-V6-TESTS.md.

## User revision v7 contract

In the player package, `AdaptiveSubtitleContrast` owns a bounded bitmap and one
in-flight PixelCopy/TextureView sample. Activity resume/pause/destruction own its
lifecycle. It never saves or transmits a frame. `CinemaSubtitleAppearance` updates
the existing SubtitleManager size/position/manual colour preferences, with a
separate opt-in automatic-contrast preference. Automatic colour never overwrites
the saved manual colour. The text renderer uses an inverse outline and overrides
foreground colour spans while retaining the original styled source for manual
restoration. Bitmap subtitles are not recoloured. Failed capture uses outlined
white. Unit/device evidence and limitations are in docs/UI-V7-TESTS.md.
