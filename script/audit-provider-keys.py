"""Verify the known historical provider credentials are absent from release APKs.

Read historical values directly from local Git objects. Never echo or persist them.
"""
from pathlib import Path
import argparse
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL_RESOURCE = "6644959:MediaLib/res/values/donottranslate.xml"

def audit(apk, patterns):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        dex = [name for name in entries if re.fullmatch(r"classes\d*\.dex", name)]
        if not dex or "resources.arsc" not in entries:
            raise SystemExit(f"{apk.name}: missing DEX or resource table")
        tail_size = max(map(len, patterns)) - 1
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                tail = b""
                while data := stream.read(4 * 1024 * 1024):
                    block = tail + data
                    if any(candidate in block for candidate in patterns):
                        raise SystemExit(f"{apk.name}: historical credential in {entry.filename}")
                    tail = block[-tail_size:]
        print(f"{apk.name}: PASS, {len(dex)} DEX, resources.arsc and all remaining ZIP entries checked")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", nargs="+", type=Path)
    args = parser.parse_args()
    original = subprocess.check_output(["git", "show", HISTORICAL_RESOURCE], cwd=ROOT)
    values = re.findall(
        r'<string name="(?:tmdb_api_key|trakt_api_key|trakt_api_secret|opensubtitles_api_key)"[^>]*>([^<]+)</string>',
        original.decode("utf-8")
    )
    if len(values) != 4:
        raise SystemExit("Expected four historical resources in local Git")
    historical_trakt = subprocess.check_output(
        ["git", "show", "HEAD:MediaLib/src/com/archos/mediacenter/utils/trakt/Trakt.java"], cwd=ROOT
    )
    values.extend(token.decode() for token in re.findall(rb'\.setAccessToken\("([0-9a-f]{32,})"\)', historical_trakt))
    patterns = tuple({value.encode(encoding) for value in values for encoding in ("utf-8", "utf-16le", "utf-16be")})
    for apk in args.apk:
        audit(apk, patterns)

if __name__ == "__main__":
    main()

