"""Verify release APKs do not contain historical provider keys or hardcoded secrets.

Standalone security audit for public repositories:
- Checks all DEX files, resources.arsc, and remaining ZIP entries in UTF-8,
  UTF-16LE, and UTF-16BE against one-way SHA-256 fingerprints of known
  historical provider credentials (no plaintext secrets stored in this script).
- Scans for hardcoded secret patterns (Trakt/OAuth client_secret values,
  Google OAuth secrets, private key blocks, and populated legacy provider keys).
"""
from pathlib import Path
import argparse
import hashlib
import re
import zipfile

# One-way SHA-256 digests of the 5 historical provider credentials
# (all ASCII alphanumeric, lengths 32 and 64). Never store plaintext values here.
HISTORICAL_CREDENTIAL_SHA256 = {
    "fe24d9159a76ef6c9ebc66a731414bb39196a45dd1587ed242ac4394fe44d992": "historical tmdb_api_key",
    "dda1281dac2847ce72c299a00aacb8b14aab391f2618e9ac2302e6b477a45f1a": "historical trakt_api_key",
    "9d032d315c6a5bb9a94e2a43efde7e88eda6dcb8a9be2cdaf932f1ca1a50be6d": "historical trakt_api_secret",
    "14006767360fc217704e0e2e3e26d4eecccd4d71832e0392a3ffb3f7f2b5834c": "historical opensubtitles_api_key",
    "182d95f4e8d64859d92da220ca76ae1cc4cfc365320fd16f03a6ad31fe594f68": "historical trakt access token",
}
HISTORICAL_LENGTHS = (32, 64)
CHUNK_SIZE = 4 * 1024 * 1024
OVERLAP_BYTES = 256

RE_ASCII_RUN = re.compile(rb"[A-Za-z0-9]{32,}")
RE_UTF16LE_RUN = re.compile(rb"(?:[A-Za-z0-9]\x00){32,}")
RE_UTF16BE_RUN = re.compile(rb"(?:\x00[A-Za-z0-9]){32,}")

# Secret patterns checked across decoded ASCII/UTF-8, UTF-16LE, and UTF-16BE runs.
FORBIDDEN_PATTERNS = (
    ("Google OAuth client secret", re.compile(rb"GOCSPX-[A-Za-z0-9_-]{20,}")),
    (
        "Embedded PEM private key",
        re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{64,}"),
    ),
    ("GitHub token", re.compile(rb"\b(?:ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,})\b")),
    (
        "Hardcoded Trakt/provider secret assignment",
        re.compile(
            rb"(?:trakt_api_secret|trakt\.client\.secret|TRAKT_CLIENT_SECRET)"
            rb"\s*[:=]\s*[\"']?[A-Za-z0-9_-]{16,}",
            re.IGNORECASE,
        ),
    ),
    (
        "Hardcoded hex client_secret",
        re.compile(rb"[\"']?client_secret[\"']?\s*[:=]\s*[\"']?[0-9a-fA-F]{32,}[\"']?"),
    ),
    (
        "Populated legacy provider resource string",
        re.compile(
            rb"<string\s+name=\"(?:tmdb_api_key|trakt_api_key|trakt_api_secret|opensubtitles_api_key)\""
            rb"[^>]*>[A-Za-z0-9_-]{16,}</string>"
        ),
    ),
    (
        "Hardcoded Trakt access token literal",
        re.compile(rb"\.setAccessToken\(\"[0-9a-fA-F]{32,}\"\)"),
    ),
)

RE_UTF16LE_PRINTABLE = re.compile(rb"(?:[\x20-\x7e]\x00){16,}")
RE_UTF16BE_PRINTABLE = re.compile(rb"(?:\x00[\x20-\x7e]){16,}")


def _check_hash_candidate(token: bytes) -> str | None:
    length = len(token)
    if length in HISTORICAL_LENGTHS:
        digest = hashlib.sha256(token).hexdigest()
        return HISTORICAL_CREDENTIAL_SHA256.get(digest)
    # Also inspect embedded substrings in moderately long contiguous runs.
    if 32 < length <= 160:
        for target_len in HISTORICAL_LENGTHS:
            if length < target_len:
                continue
            for start in range(0, length - target_len + 1):
                digest = hashlib.sha256(token[start : start + target_len]).hexdigest()
                hit = HISTORICAL_CREDENTIAL_SHA256.get(digest)
                if hit:
                    return hit
    return None


def _scan_block(block: bytes) -> str | None:
    # 1. One-way SHA-256 fingerprint checks (UTF-8/ASCII, UTF-16LE, UTF-16BE)
    for match in RE_ASCII_RUN.finditer(block):
        hit = _check_hash_candidate(match.group(0))
        if hit:
            return hit
    for match in RE_UTF16LE_RUN.finditer(block):
        hit = _check_hash_candidate(match.group(0)[::2])
        if hit:
            return hit
    for match in RE_UTF16BE_RUN.finditer(block):
        hit = _check_hash_candidate(match.group(0)[1::2])
        if hit:
            return hit

    # 2. Safe regex secret checks across raw bytes and UTF-16LE/BE text runs
    for label, pattern in FORBIDDEN_PATTERNS:
        if pattern.search(block):
            return label

    for match in RE_UTF16LE_PRINTABLE.finditer(block):
        decoded = match.group(0)[::2]
        for label, pattern in FORBIDDEN_PATTERNS:
            if pattern.search(decoded):
                return f"{label} (UTF-16LE)"

    for match in RE_UTF16BE_PRINTABLE.finditer(block):
        decoded = match.group(0)[1::2]
        for label, pattern in FORBIDDEN_PATTERNS:
            if pattern.search(decoded):
                return f"{label} (UTF-16BE)"

    return None


def audit(apk: Path) -> None:
    if not apk.is_file():
        raise SystemExit(f"{apk}: file not found")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        dex = [name for name in entries if re.fullmatch(r"classes\d*\.dex", name)]
        if not dex or "resources.arsc" not in entries:
            raise SystemExit(f"{apk.name}: missing DEX or resource table")
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            with archive.open(entry) as stream:
                tail = b""
                while data := stream.read(CHUNK_SIZE):
                    block = tail + data
                    finding = _scan_block(block)
                    if finding:
                        raise SystemExit(f"{apk.name}: {finding} detected in {entry.filename}")
                    tail = block[-OVERLAP_BYTES:]
        print(f"{apk.name}: PASS, {len(dex)} DEX, resources.arsc and all remaining ZIP entries checked")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Audit release APKs for historical provider keys and hardcoded secrets."
    )
    parser.add_argument("apk", nargs="+", type=Path)
    args = parser.parse_args()
    for apk in args.apk:
        audit(apk)


if __name__ == "__main__":
    main()


