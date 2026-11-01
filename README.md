# Editotsu anime extensions

Aniyomi-format (extensions-lib 16) extensions for [Editotsu](https://github.com/patrykjaki/editotsu).
Kept out of the app APK on purpose.

## Extensions

| Extension | Package | What it does |
|---|---|---|
| Editotsu Torrents | `eu.kanade.tachiyomi.animeextension.all.editotsutorrents` | AniList catalogue; episodes from ani.zip (AniDB ids); releases from `feed.animetosho.net` (AniDB-indexed), Nyaa RSS when that returns nothing. Videos are magnets with structured `internalData`. |

## Add the repo

Editotsu: anime repositories → add `patrykjaki/anime-extensions` (expands to the `repo` branch)
or `https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo/index.min.json`.

Aniyomi (and forks on its current store code): add
`https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo/index.min.json`
(it reads `repo.json` next to it and follows `index_v2` to `index.json`). Torrent rows play only
when the client's torrent server (TorrServer in Aniyomi) is enabled.

The repo branch carries three index files: `index.min.json` (legacy list), `index.json` (store
format) and `repo.json` (legacy meta pointing at `index.json`).
The `repo` branch holds the output of `scripts/build_repo.py`; not published yet.

## Build

```bash
./gradlew :src:all:editotsutorrents:testDebugUnitTest :src:all:editotsutorrents:assembleRelease
python3 scripts/build_repo.py          # -> repo/index.min.json, repo/apk/, repo/icon/
```

Unit tests run on recorded fixtures only. A live check of the same pipeline against the real
services is opt-in:

```bash
EDITOTSU_LIVE=1 ./gradlew :src:all:editotsutorrents:testDebugUnitTest --tests '*LiveCheck*' -i
```

Signing: `signing.properties` in the repo root (git-ignored):

```
storeFile=/path/to/anime-extensions.p12
storePassword=...
keyAlias=anime-extensions
keyPassword=...
```

or the env vars `EXT_STORE_FILE`, `EXT_STORE_PASSWORD`, `EXT_KEY_ALIAS`, `EXT_KEY_PASSWORD`.
All four or none; with none the local debug key is used, and `-PrequireReleaseKey` makes that an
error. Release signer SHA-256: `6290c3ad20ff8222c77b1a5871d8884397004e31fb0658d43db78f4f6210f408`.
An extension update must keep this key.

CI: `.github/workflows/build.yml` tests, builds with the release key, checks the signer against
`EXPECTED_CERT_DIGEST`, and publishes `repo/` to the `repo` branch on every push to `main`.
Repository secrets: `KEYSTORE_FILE` (base64 of the .p12), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`, `EXPECTED_CERT_DIGEST`.

## internalData contract (Editotsu Torrents)

`Video.internalData` is a JSON object; `Video.resolution` is set when the name states it.

```json
{"editotsu":1,"infoHash":"<40 hex>","fileIdx":4,"torrentName":"..","fileName":"..",
 "sizeBytes":0,"seeders":0,"leechers":0,"tracker":"nyaa","anidbEid":0}
```

- Raw facts only, never parsed tags (group, censored, REPACK, codec, audio are parsed in the app).
- A key whose value is unknown is left out; readers fall back to text parsing for that field.
  - `fileName`: only when the `.torrent` was read (multi-file torrents). Single-file rows don't have it.
  - `seadex`: not sent yet (SeaDex annotation is a later step).
- Extra keys (readers may ignore): `sizeApprox` (Nyaa sizes are rounded), `trusted`, `remake` (Nyaa),
  `toshoIsSeadex` (animetosho.net's own flag, not best/alt), `anidbAid`, `numFiles`,
  `source` (`animetosho` | `nyaa`, the feed, not a release tag).
- `tracker`: `nyaa`, `sukebei`, `nekobt`, `anidex`, `tokyotosho`, `unknown`.

Video URL: `magnet:?xt=urn:btih:<hash>&dn=<name>&tr=..&index=<fileIdx>`, `index` always last
(Editotsu reads it with `substringAfter("index=")`).

## Third-party code and data

No code is copied from other extension repos. API shapes were read from
[aniyomiorg/extensions-lib](https://github.com/aniyomiorg/extensions-lib) v16 (Apache-2.0, compile-only
dependency). `gradlew` and `gradle/wrapper/gradle-wrapper.jar` are Gradle's (Apache-2.0).
Test fixtures in `src/all/editotsutorrents/src/test/resources/fixtures` are API responses recorded
on 2026-11-01 from feed.animetosho.net, api.ani.zip, graphql.anilist.co and nyaa.si.
