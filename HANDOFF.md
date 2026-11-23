# Local Cinema handoff

Start with [AGENTS.md](AGENTS.md) and [docs/RELEASE.md](docs/RELEASE.md).

## Published status (`Latest`)

- Public GitHub Latest: [`v1.0.0-beta.15`](https://github.com/alnouri96/meedx/releases/tag/v1.0.0-beta.15) (`versionCode = 15`, 2026-11-22), matching source tag `v1.0.0-beta.15` (`a6d07bf8`) on `alnouri96/local-cinema` and live OTA at `https://meedx.pages.dev/api/version.json`.
- MEEDX WEB production: commit `38b29386` ([Pages deployment](https://c8878e15.meedx.pages.dev)). Keep published APKs and checksums unchanged.

## Active local work (`v1.0.0-beta.16`)

- Workspace: `G:/Local Cinema` (`upgrade-6.6.4`), **not published yet**.
- Status: All 3 refinement batches for `v1.0.0-beta.16` (`versionCode = 16`) are built as signed Release APKs, passed `audit-provider-keys.py` (`PASS`), and installed in place on TV (`10.0.0.6:5555`) and Phone (`R5CY22YT4KZ`) with 0 new crashes.

## Next steps

1. Complete user verification on TV and Phone for `v1.0.0-beta.16`.
2. Commit reviewed changes cleanly in `G:/Local Cinema` (and sync `MEEDX WEB` docs/catalog when approved).
3. Stage audited APKs under `releases/v1.0.0-beta.16/` and publish only upon explicit release approval following [docs/RELEASE.md](docs/RELEASE.md).
