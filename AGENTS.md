# Local Cinema agent guide

Local Cinema TV uses `com.meedx.localcinema.tv`. Local Cinema Manager uses `com.meedx.localcinema.manager`. The apps share `shared-providers/` and `shared-updater/`.

## Current state

- Published `v1.0.0-beta.15` (`versionCode = 15`) on 2026-11-22. It is the public GitHub Latest.
- Read [the current handoff](docs/CONTINUATION.md) for local work and verification. Use [the release procedure](docs/RELEASE.md) for builds and publication.
- [Release notes](CHANGELOG-CINEMA.md) and `releases/<tag>/checksums.txt` record each release. Older handoffs are in the private archive.

## Start here

1. Read this file and `graphify-out/GRAPH_REPORT.md` before searching across the repository. If that ignored file is missing, run `graphify update .` to generate it.
2. Check `git status --short --branch` and read [the handoff](docs/CONTINUATION.md). Keep unrelated local changes. Verify the current public tag with `gh api repos/alnouri96/meedx/releases/latest --jq .tag_name` before reporting release status.
3. Follow [Phone/AGENTS.md](Phone/AGENTS.md) or [Video/AGENTS.md](Video/AGENTS.md) for module-specific work. Read AGENTS.md in the sibling MEEDX WEB checkout for the website.
4. After any app code or resource change, build both signed Release APKs, stage them, run the credential audit, and check both devices. Follow [docs/RELEASE.md](docs/RELEASE.md) for staging and ADB.
5. After editing code or documentation, run incremental `graphify update .` in the monorepo before handing it over.

For UI/UX tasks, use [UI UX Pro Max](.codex/skills/ui-ux-pro-max/SKILL.md) and apply the [Local Cinema design rules](.codex/skills/ui-ux-pro-max/LOCAL_CINEMA_THEME.md) before designing or editing an interface.

## Source map

| Domain | Phone / Shared Paths | TV Paths |
| --- | --- | --- |
| **TV Remote & Input** | `Phone/app/src/main/java/org/proninyaroslav/libretorrent/cinema_hub/`: `FloatingTvDpadController.java`, `CinemaRemoteBridge.java`, `RemoteJoystick.java` | `Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/`: `TvLocalAdbBridge.java`, `TvRemoteAccessibilityService.java`, `TvRemoteImeService.java`, `TvRemotePermissionKeeper.java` |
| **Browser, Rendering, Zoom & Extensions** | `Phone/app/src/main/java/org/proninyaroslav/libretorrent/cinema_hub/`: `CinemaBrowser.java`, `CinemaBrowserSettingsSheet.java`, `CinemaBrowserMediaBridge.java`, `BrowserExtensions.java`, `BrowserTabsSheet.java`, `BrowserDownloadManager.java`, `SiteFaviconManager.java`<br>Assets: `Phone/app/src/main/assets/cinema-video/discover.js`, `network.js` | — |
| **Trakt, Providers & Addons** | Shared (`shared-providers/src/com/meedx/providers/`): `TraktSyncClient.java`, `StremioAddons.java`, `ProviderKeys.java`, `MultiRatingsProvider.java`, `OpenTrailersProvider.java`, `ZeroConfigSubtitles.java`<br>Phone (`cinema_hub/`): `TvHubConnectionController.java`, `ApiKeyStore.java`, `PhoneWatchStore.java` | `Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/`: `CinemaWatchStore.java`, `ApiKeyStore.java` |
| **Casting, Video Player & Dual Subtitles** | `Phone/app/src/main/java/org/proninyaroslav/libretorrent/cinema_hub/`: `TvHubActivity.java`, `TvHubSubtitlesController.java`, `PhoneVideoServer.java`, `SubtitleMatch.java` | `Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/`: `CinemaHlsActivity.java`, `CinemaLinkServer.java`, `CinemaAutoSubtitles.java`<br>`Video/src/main/java/com/archos/mediacenter/video/player/`: `PlayerActivity.java`, `CinemaSecondarySubtitle.java`, `CinemaSubtitleAppearance.java` |
| **Library, Local Media, Music & Glassmorphism UI** | `Phone/app/src/main/java/org/proninyaroslav/libretorrent/cinema_hub/`: `TvHubActivity.java`, `TvHubLibraryController.java`, `TvHubMusicController.java`, `TvHubConnectionController.java`, `CinemaCacheManager.java`, `LocalMediaPanel.java`, `MobileLibraryServer.java`, `CinemaGlassUi.java`, `PhotoCastViewer.java`, `LocalCinemaBackupManager.java`, `CastMediaForegroundService.java`, `MediaPlaybackNotificationService.java`<br>Settings (`ui/settings/`): `SettingsHeaderFragment.java`, `StorageCacheHeaderPreference.java`, `GoogleAccountHeaderPreference.java` | `Video/src/main/java/com/archos/mediacenter/video/leanback/cinema/`: `CinemaHomeFragment.java`, `CinemaLocalMediaActivity.java`, `CinemaWatchlistActivity.java`, `CinemaUi.java` |
| **Auto-Updater, Releases & Scripts** | `shared-updater/`, `version.json`, `CHANGELOG-CINEMA.md` | `script/build-both.ps1`, `script/stage-clean-release.ps1`, `script/audit-provider-keys.py` and the website release procedure |

## Preservation rules

1. **Trakt OAuth 2.0 PKCE Only:** Public app auth (`TraktSyncClient.java` / `ProviderKeys.java`) uses `client_id` `ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE`, redirect URI `localcinema://trakt-callback`, and `S256` PKCE verifier/challenge. **NO `client_secret`** anywhere in OAuth/device/refresh calls or developer key UI.
2. **Strict Manual / On-Change Sync Policy:** Phone-to-TV and Trakt full syncs run ONLY when settings/addons/keys are added or changed, or on a manual user trigger. Never add periodic, on-resume, or auto-reconnect full-sync polling (it causes flicker and redundant requests).
3. **TV Synthetic MediaDB IDs Must Be Positive (`900_000_000L+`):** Virtual Trakt/remote `Video` / `Episode` entries on TV (`CinemaHomeFragment.java`, `CinemaWatchlistActivity.java`) must use positive `900_000_000L+` synthetic IDs to avoid negative-ID startup crashes in `Video.<init>`.
4. **Release Security Audit Must Pass:** `python script/audit-provider-keys.py` must `PASS` on both TV and Phone Release APKs before any release or device rollout. Never hardcode API keys/secrets or log cookies/tokens/credentials.
5. **Architecture & Protocol Stability:** Preserve `applicationId`s (`com.meedx.localcinema.tv`, `com.meedx.localcinema.manager`), MediaLib provider authorities, signing keys, WebSocket schema (`CinemaLinkServer` <-> `TvHubActivity` on port `8765`, media HTTP port `8766`, WebDAV library port `8080`), bilingual Arabic RTL / English LTR UI, and media privacy.
6. **Mandatory `ste80-humanizer` Prose Rule:** Write all GitHub Releases notes, `README.md` files, changelogs, and `MEEDX WEB` copy with the `ste80-humanizer` skill (Pass 1 `§1–§26` removes AI tells, marketing fluff, decorative bolding, and em-dashes; Pass 2 `S1–S15` enforces short active-voice sentences, one idea per sentence, exact facts). Wrap Arabic GitHub Release sections in `<div dir="rtl" align="right">...</div>` and start every Arabic bullet with an Arabic word.
7. **Anti-Bloat Rule:** Never add release feature summaries, changelog bullets, or duplicate `SHA-256` hashes to `AGENTS.md`. On new releases, update only version numbers and dates in `Current State`; keep all release details exclusively in `CHANGELOG-CINEMA.md` and `releases/v<version>/checksums.txt`.

8. Use Local Cinema branding in product UI and public links. Keep compatibility identifiers and [third-party attribution](docs/THIRD_PARTY.md).
9. Keep Git cleanup reversible. Verify dependencies before removing files. Preserve the signing setup, native build inputs, user data, and published APKs.
10. Add every new user-facing label, button, title, message, dialog and accessibility description to both English `values/strings.xml` and Arabic `values-ar/strings.xml` in the owning app. Use resource IDs in code and preserve RTL/LTR behavior.

## Website and distribution

- The public repository for app source is `alnouri96/local-cinema`. Its integration branch for this cycle is `upgrade-6.6.4`. Keep the legacy development history in its private archive.
- Public downloads, OTA and the website use `alnouri96/meedx`. The site's publication branch is `main`. Build both APKs in this repository. Keep APKs and ZIP archives out of the website checkout.
- The OTA client uses `https://meedx.pages.dev/api/version.json`. Sync the website from `version.json`, the staged hashes, and bilingual release notes. Follow [the release procedure](docs/RELEASE.md) and the website runbook.
- Publish each approved public Android release as a regular GitHub release with `--latest`. Keep the beta suffix in the version name when applicable. Confirm the Latest tag, app manifest, and live OTA agree.
- Keep local candidates and publication separate. A publishing request must name the release or approved code changes. Finish this local verification before any push, GitHub Release, or Cloudflare deployment.
