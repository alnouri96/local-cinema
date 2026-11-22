# Local Cinema current status

[Local Cinema v1.0.0-beta.15](https://github.com/alnouri96/meedx/releases/tag/v1.0.0-beta.15) (build 15) is GitHub Latest as of 2026-11-22. The [live OTA feed](https://meedx.pages.dev/api/version.json) and [Downloads](https://meedx.pages.dev/downloads) point to the same two APKs. GitHub Releases serves the binaries and checksum file. Keep previously published files unchanged.

- [Android source](https://github.com/alnouri96/local-cinema) is public. Its `v1.0.0-beta.15` tag points to `a6d07bf8dc24c2fdaf818b916aa8bbdad45fb70a`. Use `upgrade-6.6.4` for this integration cycle. The legacy repository was archived privately with its existing history. In the legacy local checkout, `origin` points to that private archive.
- MEEDX WEB production uses commit `38b29386f4b479c1ccac9929e32dd2eec70ebdc1` and [this Pages deployment](https://c8878e15.meedx.pages.dev). This website update adds a wide home poster, dedicated download group, and bilingual changelog dialog. The live checks match the existing release and the updated notes. The published APKs and checksums are unchanged.
- Both signed Release APKs passed the provider-key audit. Verified the package IDs, ABI, build 15, and matching published signing certificates. Installed both in place. Opened the TV settings and the phone About dialog. GeckoView loaded the Downloads page, and the phone displayed its gold logo and MEEDX developer credit. Both ADB crash buffers stayed unchanged during the checks.

Use [the root handoff](../HANDOFF.md) for architecture and testing. [The release procedure](RELEASE.md) covers signing, Android installation, source tags, and website deployment. [Release notes](../CHANGELOG-CINEMA.md) list the beta.15 changes.
