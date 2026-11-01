<div align="center">

# Editotsu Extensions

| Editotsu | Aniyomi | Build |
|:---:|:---:|:---:|
| [![Install](https://img.shields.io/badge/Add%20repo-Editotsu-1f8a8a?style=flat)](https://intradeus.github.io/http-protocol-redirector/?r=editotsu://add-repo?type=anime%26url=https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo/index.min.json) | [![Install](https://img.shields.io/badge/Add%20repo-Aniyomi-d32f2f?style=flat)](https://intradeus.github.io/http-protocol-redirector/?r=aniyomi://add-repo?url=https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo/index.min.json) | [![Build](https://github.com/patrykjaki/anime-extensions/actions/workflows/build.yml/badge.svg)](https://github.com/patrykjaki/anime-extensions/actions/workflows/build.yml) |

</div>

## Install

Tap a button above, or add this URL as an anime extension repo:

```
https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo/index.min.json
```

APKs are in the [`repo` branch](https://github.com/patrykjaki/anime-extensions/tree/repo/apk).
Signing key SHA-256: `6290c3ad20ff8222c77b1a5871d8884397004e31fb0658d43db78f4f6210f408`.

## Editotsu Torrents

Anime torrents from public indexers. Catalogue from [AniList](https://anilist.co), episodes
matched by AniDB id via [ani.zip](https://api.ani.zip).

- Releases are checked against the episode by name as well (season, special editions, split
  halves, other shows with the same title).
- Batches resolve to the episode's file inside the torrent.
- Older versions of a release (v1 next to v2, the original next to a REPACK) are hidden; the
  setting "Show older versions of a release" lists them.
- Codec, bit depth, HDR, audio and subtitle tracks come from the indexer's MediaInfo report when
  it has one, otherwise from the release name.

Playing magnet links needs Editotsu (built-in torrent engine) or Aniyomi with TorrServer. Editotsu
reads the release data below; other apps show the title, which ends with a seeders/size line.

## Issues

[Open an issue](https://github.com/patrykjaki/anime-extensions/issues) with the show, the episode
and the release name.

## Development

```bash
./gradlew :src:all:editotsutorrents:testDebugUnitTest :src:all:editotsutorrents:assembleRelease
python3 scripts/build_repo.py   # writes repo/: index.min.json, index.json, repo.json, apk/, icon/
EDITOTSU_LIVE=1 ./gradlew :src:all:editotsutorrents:testDebugUnitTest --tests '*LiveCheck*' -i   # live services
```

Tests run on recorded responses in `src/all/editotsutorrents/src/test/resources/fixtures`.
Release signing reads `signing.properties` (git-ignored: `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) or `EXT_STORE_FILE`, `EXT_STORE_PASSWORD`, `EXT_KEY_ALIAS`,
`EXT_KEY_PASSWORD`; without them the debug key is used. CI
([`build.yml`](.github/workflows/build.yml)) builds with the release key, rejects any other signer
and publishes `repo/` to the `repo` branch on every push to `main`.

### `Video.internalData` (version 1)

Unknown keys are left out. `media.from` is `mediainfo` (the file) or `name` (the release name).
Languages are BCP-47. The video URL is a magnet link ending in `&index=<fileIdx>`.

```jsonc
{
  "editotsu": 1,
  "infoHash": "05bd3a45…", "fileIdx": 4,
  "torrentName": "[Group] Show - S01 [BD 1080p]", "fileName": "Show - S01E05.mkv",
  "sizeBytes": 336967292, "seeders": 42, "leechers": 5, "tracker": "<tracker id>",
  "anidbEid": 271708, "numFiles": 28, "source": "<indexer id>",
  "tags":  { "from": "name", "group": "Group", "source": "BD", "service": "CR", "revision": "v2" },
  "media": { "from": "mediainfo", "codec": "AV1", "bitDepth": 10, "width": 1920, "height": 1080,
             "audio": [ { "lang": "ja", "codec": "Opus", "channels": 2 } ],
             "subtitles": [ { "lang": "en", "format": "ASS", "forced": false } ] }
}
```

## Disclaimer

This repo hosts no media or torrent files; the extension reads public indexers and passes their
links to the app. Not affiliated with Aniyomi, AniList, AniDB, any indexer or any release group. API stubs from [aniyomiorg/extensions-lib](https://github.com/aniyomiorg/extensions-lib)
(Apache-2.0, compile-only).
