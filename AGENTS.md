# Local Cinema agent guide

Local Cinema TV uses `com.meedx.localcinema.tv` (`Video/`). Local Cinema Manager uses `com.meedx.localcinema.manager` (`Phone/`). Both apps share `shared-providers/` and `shared-updater/`.

## Current state

- **Official Repository & Releases:** `alnouri96/local-cinema` (default branch: `main`).
- **Official OTA Feed:** `https://meedx.pages.dev/api/v2/version.json` (allowlist strictly limited to `github.com/alnouri96/local-cinema` and `meedx.pages.dev`).
- **Published Latest:** `v1.0.0-beta.19` (`versionCode = 19`, 2026-11-24) — the final beta release.
- **Next Release Target:** `v1.0.0` (`versionCode = 20`, `versionName = "1.0.0"`) — first stable release.
- Active work and status: see [HANDOFF.md](HANDOFF.md). Build and release procedure: see [docs/RELEASE.md](docs/RELEASE.md).
- Release history lives exclusively in [CHANGELOG-CINEMA.md](CHANGELOG-CINEMA.md) and `releases/<tag>/checksums.txt`.

## Module routing & shared map

- **TV app:** [Video/AGENTS.md](Video/AGENTS.md)
- **Phone app:** [Phone/AGENTS.md](Phone/AGENTS.md)
- **Website & OTA:** sibling `MEEDX WEB/AGENTS.md` (`https://meedx.pages.dev`)
- **Shared roots:**
  - `shared-providers/src/com/meedx/providers/`: `UnifiedSubtitleConfig.java`, `UnifiedSubtitleEngine.java`, `SubtitleArabicTranslator.java`, `TraktSyncClient.java`, `StremioAddons.java`, `ProviderKeys.java`, `ApiKeyStore.java`, `SubtitleMatch.java`, `MultiRatingsProvider.java`, `OpenTrailersProvider.java`, `ZeroConfigSubtitles.java`, `LiveTvPreferences.java`, `LiveChannelFavorites.java`
  - `shared-updater/src/com/meedx/updates/`: `AppUpdates.java`, `UpdateConfig.java`, `ReleaseSigner.java`, `ProjectLinks.java`, `UpdateFileProvider.java`
  - `script/`: `build-both.ps1`, `build-cinema.ps1`, `stage-clean-release.ps1`, `audit-provider-keys.py`, `tv-capture.py`

## Permanent engineering rules

1. **Modular & Isolated Code:** Build every new feature, screen, or sheet in its own focused class/file (never stack code into large coordinators like `CinemaHomeFragment.java` or `TvHubActivity.java`). Register every new file immediately in the owning module's `AGENTS.md` map.
2. **No Duplication / Reuse Existing Features:** Reuse existing pickers, dialogs, shared providers, and helpers (for example, all TV media/subtitle browsing routes through `CinemaFileBrowserActivity` and all subtitle config routes through `UnifiedSubtitleConfig`).
3. **Sequential Gradle Builds Only:** Run Gradle commands one at a time with `--no-daemon --max-workers=2`.
4. **Targeted Read Policy:** When editing one module (TV-only, Phone-only, or Website-only), read only this file plus that module's `AGENTS.md`, then open target files directly.

## Preservation & security guardrails

1. **Trakt OAuth 2.0 PKCE Only:** Public auth (`TraktSyncClient.java`, `ProviderKeys.java`) uses `client_id` `ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE`, redirect `localcinema://trakt-callback`, and `S256` PKCE. **Never add `client_secret`**.
2. **Manual / On-Change Sync Policy:** Full Phone-to-TV and Trakt syncs run only on explicit user trigger or when settings/keys change. Never add periodic or on-resume full-sync polling.
3. **Positive TV Synthetic MediaDB IDs (`900_000_000L+`):** Virtual/remote `Video`/`Episode` objects on TV must use positive `900_000_000L+` IDs.
4. **Provider-Key Security Audit (`PASS`):** `python script/audit-provider-keys.py` must return `PASS` on both TV and Phone Release APKs before any release or install. Never hardcode API keys/secrets or log credentials.
5. **Architecture, Ports & Bilingual Stability:** Preserve `applicationId`s (`com.meedx.localcinema.tv`, `com.meedx.localcinema.manager`), `MediaLib` provider authorities, signing setup, ports (`8765` WS, `8766` HTTP media, `8080` WebDAV library), media privacy, and [third-party attribution](docs/THIRD_PARTY.md). Support Arabic (`RTL`) and English (`LTR`) across all UI.
6. **Visual Identity:** Follow Soft Black `#171717`, Gold `#E5A93B`, White `#FFFFFF`. Never allow clipped focus rings or wrapped single-line buttons.
7. **Safe Reversible Cleanup:** Preserve signing setup, native inputs, user data, and published APKs.

## Distribution & release policy

- **Single Source & Release Repo:** `alnouri96/local-cinema` (`main`) is the sole official repository for Android source code, tags, and published GitHub Release APKs (`LocalCinema-TV-<tag>.apk`, `LocalCinema-Manager-Phone-<tag>.apk`, `checksums.txt`).
- **OTA Channel:** In-app updates read exclusively from `https://meedx.pages.dev/api/v2/version.json` and download exclusively from `github.com/alnouri96/local-cinema` or `meedx.pages.dev`.
- **Approval Gate:** Never push, create a GitHub Release, or deploy Cloudflare Pages without explicit approval; publish approved releases as regular GitHub Releases (`--latest`, `--prerelease=false`) on `alnouri96/local-cinema` and verify via `npm run verify -- --live` in `MEEDX WEB`.
