# Third-party sources and compatibility

| Component | Upstream | Notices |
| --- | --- | --- |
| Android TV and shared playback | [NOVA Video Player and Archos AVP](https://github.com/nova-video-player/aos-AVP) | [Root license](../LICENSE.txt), [TV license](../Video/LICENSE.txt), [MediaLib license](../MediaLib/LICENSE.txt), [FileCoreLibrary license](../FileCoreLibrary/LICENSE.txt) |
| Android phone torrent client | [LibreTorrent](https://gitlab.com/proninyaroslav/libretorrent) | [Phone license](../Phone/LICENSE.md) |

Keep upstream copyright headers, license texts, and relevant notices. The release owner must confirm that the distribution includes the required notices and access to corresponding source. The archived upstream release notes are under [docs/upstream](upstream/).

Use Local Cinema and Local Cinema Manager in app labels, support URLs, documentation, and marketing. Keep the package names that Java and Android already use internally, serialized formats, provider authorities, preferences, database schemas, and upgrade paths. For example, the Phone migration still reads the historical backup directory, and its torrent client identity stays compatible with existing trackers. The TV bootstrap fetches the pinned engine by its original upstream release identifier.
