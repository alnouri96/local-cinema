# Local Cinema current status

GitHub Latest currently lists `v1.0.0-beta.14`. The signed `v1.0.0-beta.15` pair is staged under `releases/v1.0.0-beta.15/` and passed the provider-key audit. Verify the current release against [GitHub Latest](https://github.com/alnouri96/meedx/releases/latest) and [the live OTA feed](https://meedx.pages.dev/api/version.json) before quoting the release status.

Use [the root handoff](../HANDOFF.md) for architecture and testing. [The release procedure](RELEASE.md) covers Android signing, staging, ADB installation, the public source tag, and website deployment. [Release notes](../CHANGELOG-CINEMA.md) list the beta.15 changes. Keep previously published APKs and checksums available.

Continue on the clean publication repository's `upgrade-6.6.4` branch and use `main` as its public default. Preserve the older development history in the private archive. Never push that history to the clean public remote.
