# Local Cinema agent guide

Local Cinema TV uses `com.meedx.localcinema.tv` (`Video/`). Local Cinema Manager uses `com.meedx.localcinema.manager` (`Phone/`). Both apps share `shared-providers/` and `shared-updater/`.

## Current state

- Published Latest: `v1.0.0-beta.16` (`versionCode = 16`, 2026-11-23) on `alnouri96/meedx` and `alnouri96/local-cinema`.
- Active local work: see [HANDOFF.md](HANDOFF.md). Release builds and publication: see [docs/RELEASE.md](docs/RELEASE.md).
- Release history lives exclusively in [CHANGELOG-CINEMA.md](CHANGELOG-CINEMA.md) and `releases/<tag>/checksums.txt`.

## Module routing & shared map

- **TV app:** [Video/AGENTS.md](Video/AGENTS.md)
- **Phone app:** [Phone/AGENTS.md](Phone/AGENTS.md)
- **Website & OTA:** sibling `MEEDX WEB/AGENTS.md` (`alnouri96/meedx`, `main`)
- **Shared roots:**
  - `shared-providers/src/com/meedx/providers/`: `TraktSyncClient.java`, `StremioAddons.java`, `ProviderKeys.java`, `ApiKeyStore.java`, `SubtitleMatch.java`, `MultiRatingsProvider.java`, `OpenTrailersProvider.java`, `ZeroConfigSubtitles.java`, `LiveTvPreferences.java`, `LiveChannelFavorites.java`
  - `shared-updater/src/com/meedx/updates/`: `AppUpdates.java`, `UpdateConfig.java`, `ReleaseSigner.java`, `ProjectLinks.java`, `UpdateFileProvider.java`
  - `script/`: `build-both.ps1`, `build-cinema.ps1`, `stage-clean-release.ps1`, `audit-provider-keys.py`, `tv-capture.py`

## Permanent engineering rules

1. **Modular & Isolated Code:** Build every new feature, screen, or sheet in its own focused class/file (never stack code into large coordinators like `CinemaHomeFragment.java` or `TvHubActivity.java`). Register every new file immediately in the owning module's `AGENTS.md` map so future edits stay isolated.
2. **No Duplication / Reuse Existing Features:** Never build a duplicate tool, picker, or helper when an existing feature can be reused or extended (for example, all TV video, image, subtitle, and media browsing must route through the unified `CinemaFileBrowserActivity`).
3. **Targeted Read Policy:** When editing one module (TV-only, Phone-only, or Website-only), read only this file plus that module's `AGENTS.md`, then open target files directly with `rg` — skip unrelated modules and archive docs. Read [UI UX Pro Max](.codex/skills/ui-ux-pro-max/SKILL.md) and [LOCAL_CINEMA_THEME.md](.codex/skills/ui-ux-pro-max/LOCAL_CINEMA_THEME.md) only for UI/UX tasks.

## Preservation & security guardrails

1. **Trakt OAuth 2.0 PKCE Only:** Public auth (`TraktSyncClient.java`, `ProviderKeys.java`) uses `client_id` `ysZZKQ4X6LcHghcKf1ReOIsuzELNIcygal7ZUBYSwSE`, redirect `localcinema://trakt-callback`, and `S256` PKCE. **Never add `client_secret`** anywhere in OAuth/device/refresh calls or developer UI.
2. **Manual / On-Change Sync Policy:** Full Phone-to-TV and Trakt syncs run only on explicit user trigger or when settings/addons/keys change. Never add periodic, on-resume, or auto-reconnect full-sync polling.
3. **Positive TV Synthetic MediaDB IDs (`900_000_000L+`):** Virtual/remote `Video`/`Episode` objects on TV must use positive `900_000_000L+` IDs (`Video.<init>` crashes on negative IDs).
4. **Provider-Key Security Audit (`PASS`):** `python script/audit-provider-keys.py` must return `PASS` on both TV and Phone Release APKs before any release or install. Never hardcode API keys/secrets or log tokens, cookies, or credentials.
5. **Architecture, Ports & Bilingual Stability:** Preserve `applicationId`s (`com.meedx.localcinema.tv`, `com.meedx.localcinema.manager`), `MediaLib` provider authorities, signing setup, ports (`8765` WS, `8766` HTTP media, `8080` WebDAV library), media privacy, and [third-party attribution](docs/THIRD_PARTY.md). Add every user-facing string to both `values/strings.xml` and `values-ar/strings.xml` using resource IDs with RTL/LTR support.
6. **Visual Identity & Verification Gate (UI tasks):** Follow Soft Black `#171717`, Gold `#E5A93B`, White `#FFFFFF`. Never allow clipped gold focus rings or scrolled cards overlapping headers; verify UI edits on the real device before finishing.
7. **Prose & Anti-Bloat:** Apply `ste80-humanizer` to GitHub Releases notes, `README.md`, `CHANGELOG-CINEMA.md`, and `MEEDX WEB` copy (wrap Arabic GitHub Release sections in `<div dir="rtl" align="right">...</div>`). Never add changelog bullets or SHA-256 hashes to `AGENTS.md`.
8. **Safe Reversible Cleanup:** Verify dependencies before deleting files; preserve signing setup, native inputs, user data, and published APKs.

## Distribution & release policy

- App source lives in `alnouri96/local-cinema` (`upgrade-6.6.4`). Public downloads, website, and OTA (`https://meedx.pages.dev/api/version.json`) live in `alnouri96/meedx` (`main`). Keep APKs, ZIPs, signing files, and personal data out of `MEEDX WEB`.
- Keep local candidates separate from publication. Never push, create a GitHub Release, or deploy Cloudflare Pages without explicit approval; publish approved releases as regular GitHub Releases with `--latest` and verify with `gh api repos/alnouri96/meedx/releases/latest --jq .tag_name`.
