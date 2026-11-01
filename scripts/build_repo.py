#!/usr/bin/env python3
"""Build the extension repo from built release APKs.

Usage: python3 scripts/build_repo.py [out_dir] [base_url]
  out_dir  default repo/
  base_url where out_dir will be served; default the `repo` branch of patrykjaki/anime-extensions

Writes three index files so every client in the aniyomi family can add the repo:
  index.min.json  legacy list (Dantotsu/editotsu, older Aniyomi)
  index.json      store format (name/badgeLabel/signingKey/contact/extensionList), current Aniyomi + editotsu
  repo.json       legacy meta with index_v2 -> index.json. Aniyomi needs it next to index.min.json;
                  editotsu's "owner/repo" shorthand fetches it first and follows index_v2.
plus apk/<apk> and icon/<pkg>.png.
"""
import hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "repo"
BASE_URL = (sys.argv[2] if len(sys.argv) > 2 else
            "https://raw.githubusercontent.com/patrykjaki/anime-extensions/repo").rstrip("/")
STORE_NAME = "Editotsu"
WEBSITE = "https://github.com/patrykjaki/anime-extensions"
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
BUILD_TOOLS = max((d for d in (SDK / "build-tools").iterdir() if (d / "aapt").exists()),
                  key=lambda d: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", d.name)])
AAPT = BUILD_TOOLS / "aapt"


def _build_tools_key(apksigner: Path):
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", apksigner.parent.name)]


def find_apksigner() -> Path | None:
    """apksigner from PATH, else the newest one in the Android SDK build-tools."""
    if found := shutil.which("apksigner"):
        return Path(found)
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if sdk := os.environ.get(env):
            candidates.extend((Path(sdk) / "build-tools").glob("*/apksigner"))
    candidates.extend((SDK / "build-tools").glob("*/apksigner"))
    return max(candidates, key=_build_tools_key, default=None)


def signer_sha256(apk: Path) -> str:
    apksigner = find_apksigner()
    if apksigner is None:
        raise RuntimeError(
            f"apksigner not found for {apk}; checked PATH, $ANDROID_HOME/build-tools/*/apksigner "
            f"and $ANDROID_SDK_ROOT/build-tools/*/apksigner"
        )
    proc = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                          capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    # apksigner prints "Signer #1 certificate SHA-256 digest: ..." in older versions and
    # "Signer (minSdkVersion=..) certificate SHA-256 digest: ..." in newer ones.
    match = re.search(
        r"(?:Signer #1 |Signer \([^)]*\) )?certificate SHA-256 digest:\s*([0-9a-fA-F]{64})",
        out,
    )
    if proc.returncode != 0 or match is None:
        raise RuntimeError(
            f"could not read the signer certificate from {apk}\n"
            f"apksigner: {apksigner}\n"
            f"exit code: {proc.returncode}\n"
            f"stdout:\n{proc.stdout}\n"
            f"stderr:\n{proc.stderr}"
        )
    return match.group(1).lower()


def source_id(name: str, lang: str, version_id: int = 1) -> int:
    # same as AnimeHttpSource.generateId: first 8 bytes of md5("name/lang/versionId"), sign bit cleared
    d = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    return int.from_bytes(d[:8], "big") & 0x7FFFFFFFFFFFFFFF


def meta(badging: str, key: str):
    m = re.search(rf"meta-data: name='{re.escape(key)}' value='([^']*)'", badging)
    return m.group(1) if m else None


def main():
    apks = sorted(p for p in ROOT.glob("src/*/*/build/outputs/apk/release/*.apk")
                  if not p.name.endswith("-unsigned.apk"))
    if not apks:
        sys.exit("no release apks; run ./gradlew assembleRelease first")
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "apk").mkdir(parents=True)
    (OUT / "icon").mkdir()
    index = []
    store_exts = []
    signers = set()
    for apk in apks:
        badging = subprocess.check_output([str(AAPT), "dump", "--include-meta-data", "badging", str(apk)]).decode()
        pkg = re.search(r"package: name='([^']+)'", badging).group(1)
        code = int(re.search(r"versionCode='([^']+)'", badging).group(1))
        version = re.search(r"versionName='([^']+)'", badging).group(1)
        label = re.search(r"application-label:'([^']*)'", badging).group(1)
        name = meta(badging, "aniyomix.name") or label.removeprefix("Aniyomi: ")
        nsfw = int(meta(badging, "tachiyomi.animeextension.nsfw") or 0)
        lang = apk.parent.parent.parent.parent.parent.parent.name  # src/<lang>/<ext>/build/outputs/apk/release
        out_name = apk.name.removesuffix("-release.apk") + ".apk"
        shutil.copy2(apk, OUT / "apk" / out_name)
        icon = re.findall(r"application-icon-\d+:'([^']+)'", badging)[-1]
        with zipfile.ZipFile(apk) as z:
            (OUT / "icon" / f"{pkg}.png").write_bytes(z.read(icon))
        signers.add(signer_sha256(apk))
        lib = meta(badging, "aniyomix.extensionLib") or version.rsplit(".", 1)[0]
        torrent = meta(badging, "tachiyomi.animeextension.torrent") == "1"
        sid = source_id(name, lang)
        store_exts.append({
            "name": name,
            "packageName": pkg,
            "resources": {"apkUrl": f"{BASE_URL}/apk/{out_name}", "iconUrl": f"{BASE_URL}/icon/{pkg}.png"},
            "extensionLib": lib,
            "versionCode": code,
            "versionName": version,
            "contentWarning": "NSFW" if nsfw else "SAFE",
            "isTorrent": torrent,
            "sources": [{"id": sid, "name": name, "language": lang, "homeUrl": "https://animetosho.net"}],
        })
        index.append({
            "name": label,
            "pkg": pkg,
            "apk": out_name,
            "lang": lang,
            "code": code,
            "version": version,
            "nsfw": nsfw,
            "hasReadme": 0,
            "hasChangelog": 0,
            "sources": [{
                "name": name,
                "lang": lang,
                "id": str(sid),
                "baseUrl": "https://animetosho.net",
            }],
        })
    if len(signers) != 1:
        sys.exit(f"apks are signed with {len(signers)} different keys: {signers}")
    key = signers.pop()
    compact = dict(ensure_ascii=False, separators=(",", ":"))
    (OUT / "index.min.json").write_text(json.dumps(index, **compact))
    (OUT / "index.json").write_text(json.dumps({
        "name": STORE_NAME,
        "badgeLabel": STORE_NAME,
        "signingKey": key,
        "contact": {"website": WEBSITE, "discord": None},
        "extensionList": {"extensions": store_exts},
        "extensionListUrl": None,
    }, **compact))
    (OUT / "repo.json").write_text(json.dumps({
        "index_v2": f"{BASE_URL}/index.json",
        "meta": {"name": STORE_NAME, "shortName": STORE_NAME, "website": WEBSITE, "signingKeyFingerprint": key},
    }, ensure_ascii=False, indent=2))
    for e in index:
        print(f"{e['pkg']} {e['version']} -> {OUT / 'apk' / e['apk']}")


if __name__ == "__main__":
    main()
