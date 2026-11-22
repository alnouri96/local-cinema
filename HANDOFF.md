# Local Cinema handoff

Start with [AGENTS.md](AGENTS.md), [the current status](docs/CONTINUATION.md), and [the release procedure](docs/RELEASE.md).

## Repositories and architecture

- [Local Cinema](https://github.com/alnouri96/local-cinema) holds corresponding Android source. Use `upgrade-6.6.4` for this integration cycle and named tags for released APKs. The old source repository has a separate private archive.
- `Video/` builds the Android TV app (`com.meedx.localcinema.tv`). It uses Leanback for browsing, with local playback, add-ons, and live channels.
- `Phone/` builds Local Cinema Manager (`com.meedx.localcinema.manager`). It contains TV Hub, the floating remote, GeckoView, downloads, and phone music playback.
- `shared-providers/` contains Trakt PKCE and add-on integrations. `shared-updater/` parses the OTA feed, verifies downloads, and checks the official APK signer. See [third-party licenses](docs/THIRD_PARTY.md).
- [MEEDX WEB](https://github.com/alnouri96/meedx) contains the website source and public Release assets. The website uses Cloudflare Pages direct upload and publishes from `main`.

## Delivery and device checks

- Both apps use [the MEEDX OTA feed](https://meedx.pages.dev/api/version.json). It uses bilingual notes and serves APK links from the public distribution repository. Match the feed, signed APKs, source tag, and GitHub Latest.
- Release builds use R8. Official builds check the installed certificate against the release signer. Keep signing configuration local. Separately signed verification builds can use `-PlocalCinemaOfficialSigner=false`.
- Build and stage the APKs under `releases/<tag>/`. Run the key audit and compare the Android crash buffers before and after installing in place. Use the configured phone and TV device serials from the local ADB environment.
- Keep full sync manual or driven by supported settings changes. Android backup XML defines which local settings and favorites the system backup can copy. Preserve the distinction between video, music, Library, and Local Media. Keep ports `8765`, `8766`, and `8080`.

## Published status

[Local Cinema v1.0.0-beta.15](https://github.com/alnouri96/meedx/releases/tag/v1.0.0-beta.15) is GitHub Latest. Both apps are open source and free. The [corresponding source tag](https://github.com/alnouri96/local-cinema/tree/v1.0.0-beta.15) is public. The old development repository remains private and archived. Develop from the public checkout rather than pushing the legacy Git history.

## Current work

- See [release notes](CHANGELOG-CINEMA.md) for the beta.15 changes, including Iraq live channels, favorites, torrent preparation, and the floating remote.
- Use [UI UX Pro Max](.codex/skills/ui-ux-pro-max/SKILL.md) and [the Local Cinema theme](.codex/skills/ui-ux-pro-max/LOCAL_CINEMA_THEME.md). Use Soft Black `#171717`, Gold `#E5A93B`, and White `#FFFFFF`. Check Arabic RTL, English LTR, and D-pad focus on TV.
- Use the original artwork under the website's `public/assets/artwork/` and app-owned previews with demo data. Review all new screenshots for third-party artwork and personal data.
- Download Center uses the existing download engine. Work on a unified engine remains separate.
