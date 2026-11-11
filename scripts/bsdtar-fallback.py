#!/usr/bin/env python3
"""A stand-in for `bsdtar xf <archive>` when the runner hasn't got bsdtar (libarchive-tools).

fetch-proot.sh unpacks two kinds of archive: Termux's .deb packages, which are ar archives with
data.tar.* members, and Termux's proot-x.y.z.zip. Python's tarfile reads the members and the
zipfile module the zip, so a build doesn't hang on apt reaching Ubuntu's mirrors.

It only handles `xf` (extract, keeping paths), which is all the script uses.
"""

import sys
import tarfile
import zipfile
import pathlib


def extract_ar(path: pathlib.Path, dest: pathlib.Path) -> None:
    """Unpack a .deb: ar members (debian-binary, control.tar.*, data.tar.*) in place, each member's contents."""
    blob = path.read_bytes()
    if not blob.startswith(b"!<arch>\n"):
        sys.exit(f"{path}: not an ar archive")
    at = 8
    while at + 60 <= len(blob):
        header = blob[at : at + 60]
        name = header[0:16].decode(errors="replace").strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = blob[at + 60 : at + 60 + size]
        at += 60 + size + (size & 1)  # members are padded to an even length
        # Only the package's data.tar.* is wanted, as with bsdtar (which would write all of them).
        if name.startswith("data.tar"):
            (dest / name).write_bytes(body)


def main() -> None:
    args = sys.argv[1:]
    if len(args) != 2 or args[0] != "xf":
        sys.exit("usage: bsdtar-fallback.py xf <archive>")
    archive = pathlib.Path(args[1])
    dest = pathlib.Path.cwd()
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    elif archive.read_bytes()[:4] == b"!<ar":
        extract_ar(archive, dest)
        for member in dest.glob("data.tar.*"):
            with tarfile.open(member) as t:
                t.extractall(dest)
            member.unlink()
    else:
        with tarfile.open(archive) as t:
            t.extractall(dest)


if __name__ == "__main__":
    main()
