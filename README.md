# Local Cinema

<p>
  <a href="https://github.com/alnouri96/local-cinema/releases/latest">
    <img src="https://img.shields.io/github/v/release/alnouri96/meedx?style=for-the-badge&label=Latest_Release&color=E5A93B&labelColor=171717&logo=github&logoColor=FFFFFF" alt="Latest Release" />
  </a>
  <a href="https://meedx.pages.dev/tv">
    <img src="https://img.shields.io/badge/Android_TV-Download_APK-E5A93B?style=for-the-badge&logo=android&logoColor=FFFFFF&labelColor=171717" alt="Download Local Cinema TV APK" />
  </a>
  <a href="https://meedx.pages.dev/phone">
    <img src="https://img.shields.io/badge/Android_Phone-Manager_APK-E5A93B?style=for-the-badge&logo=android&logoColor=FFFFFF&labelColor=171717" alt="Download Local Cinema Manager Phone APK" />
  </a>
  <a href="https://meedx.pages.dev/downloads">
    <img src="https://img.shields.io/badge/MEEDX-Downloads_%26_Guide-E5A93B?style=for-the-badge&logo=googlechrome&logoColor=FFFFFF&labelColor=171717" alt="Downloads and Setup Guide" />
  </a>
</p>

Local Cinema TV plays your library on Android TV from device storage, USB, or SMB. Local Cinema Manager controls the TV and casts supported media from your phone over the local network.

Both apps are **open source and 100% free**.

التطبيقان **مفتوحا المصدر ومجانيان بالكامل**. يشغّل تطبيق التلفاز مكتبتك من وحدة تخزين أو من مشاركة SMB. يدير تطبيق الهاتف التلفاز ويرسل الوسائط إليه عبر الشبكة المحلية.

[Source code / الكود المصدري](https://github.com/alnouri96/local-cinema) · [Third-party licenses / تراخيص الجهات الخارجية](docs/THIRD_PARTY.md)

[Download the TV and phone apps](https://meedx.pages.dev/downloads) · [Setup and add-ons](https://meedx.pages.dev/apps/local-cinema) · [Release notes](CHANGELOG-CINEMA.md)

## Source and development

Clone with the pinned native submodules to build either app.

```sh
git clone --recurse-submodules https://github.com/alnouri96/local-cinema.git
```

| Path | Purpose |
| --- | --- |
| [Video](Video/) | Android TV interface and player |
| [Phone](Phone/) | Android companion, browser, casting, and TV control |
| [MediaLib](MediaLib/) and [FileCoreLibrary](FileCoreLibrary/) | Media indexing, playback integration, and file access |
| [shared-providers](shared-providers/) and [shared-updater](shared-updater/) | Provider clients and app updates |

Start with [AGENTS.md](AGENTS.md) and [HANDOFF.md](HANDOFF.md). Use the [release procedure](docs/RELEASE.md) to build signed APKs and verify them on both devices. See [third-party sources and licenses](docs/THIRD_PARTY.md) for attribution.

## Website and releases

The MEEDX WEB checkout tracks the [public site repository](https://github.com/alnouri96/meedx). It serves the download links, setup guide, and OTA feed. Build the Android packages here. Use the website checkout's `docs/RELEASE.md` for the public release and Cloudflare Pages steps.
