#!/usr/bin/env python3
"""Bounded, deterministic transport for the final sanitized C0-B bundle.

Only the already-validated 26 JSON files cross the GitHub Actions job boundary.
The raw SSH stream and the twelve pre-bundle capture files are never accepted by
this module.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import gzip
import hashlib
import io
import os
import re
import shutil
import stat
import sys
import tarfile
import tempfile
import zlib
from pathlib import Path

from contract import C0BError, ITEM_IDS


MAX_ARCHIVE_BYTES = 196_608
MAX_BASE64_CHARS = 262_144
OUTPUT_CHUNK_COUNT = 4
OUTPUT_CHUNK_CHARS = 65_536
MAX_MEMBER_BYTES = 131_072
MAX_PAYLOAD_BYTES = 1_048_576
MAX_TAR_BYTES = 1_179_648

EXPECTED_RELATIVE_PATHS = tuple(
    sorted(
        ("diff.json", "snapshot.json")
        + tuple(f"diff-items/{item_id}.json" for item_id in ITEM_IDS)
        + tuple(f"items/{item_id}.json" for item_id in ITEM_IDS)
    )
)
EXPECTED_TOP_LEVEL = ("diff-items", "diff.json", "items", "snapshot.json")
BASE64_TEXT = re.compile(r"[A-Za-z0-9+/]*={0,2}")
HEX64 = re.compile(r"[0-9a-f]{64}")
POSITIVE_INTEGER = re.compile(r"[1-9][0-9]{0,6}")


def _require_regular_directory(path: Path, label: str) -> None:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as error:
        raise C0BError(f"{label} does not exist") from error
    if path.is_symlink() or not stat.S_ISDIR(mode):
        raise C0BError(f"{label} must be a regular directory")


def _require_new_path(path: Path, label: str) -> None:
    if path.exists() or path.is_symlink():
        raise C0BError(f"{label} must not already exist")
    _require_regular_directory(path.parent, f"{label} parent")


def _read_regular_file(path: Path, label: str) -> bytes:
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise C0BError(f"{label} must be a regular file")
        if before.st_size < 1 or before.st_size > MAX_MEMBER_BYTES:
            raise C0BError(f"{label} size is outside the transport boundary")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            payload = source.read(MAX_MEMBER_BYTES + 1)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    identity_before = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
    )
    identity_after = (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
    )
    if identity_before != identity_after or len(payload) != before.st_size:
        raise C0BError(f"{label} changed while it was read")
    return payload


def _bundle_payloads(path: Path) -> dict[str, bytes]:
    _require_regular_directory(path, "evidence directory")
    entries = sorted(path.iterdir(), key=lambda entry: entry.name)
    if [entry.name for entry in entries] != list(EXPECTED_TOP_LEVEL):
        raise C0BError("evidence directory has unexpected top-level entries")
    for directory_name in ("diff-items", "items"):
        directory = path / directory_name
        _require_regular_directory(directory, directory_name)
        expected_names = sorted(f"{item_id}.json" for item_id in ITEM_IDS)
        observed = sorted(directory.iterdir(), key=lambda entry: entry.name)
        if [entry.name for entry in observed] != expected_names:
            raise C0BError(f"{directory_name} has an unexpected file set")

    payloads: dict[str, bytes] = {}
    total = 0
    for relative_path in EXPECTED_RELATIVE_PATHS:
        payload = _read_regular_file(path / relative_path, relative_path)
        total += len(payload)
        if total > MAX_PAYLOAD_BYTES:
            raise C0BError("bundle payload exceeds the transport boundary")
        payloads[relative_path] = payload
    return payloads


def _canonical_tar_bytes(payloads: dict[str, bytes]) -> bytes:
    if tuple(payloads) != EXPECTED_RELATIVE_PATHS:
        raise C0BError("transport payload order is not canonical")
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for relative_path in EXPECTED_RELATIVE_PATHS:
            payload = payloads[relative_path]
            info = tarfile.TarInfo(relative_path)
            info.size = len(payload)
            info.mode = 0o644
            info.uid = 0
            info.gid = 0
            info.mtime = 0
            info.uname = ""
            info.gname = ""
            info.type = tarfile.REGTYPE
            archive.addfile(info, io.BytesIO(payload))
    serialized = output.getvalue()
    if len(serialized) > MAX_TAR_BYTES:
        raise C0BError("canonical tar exceeds the transport boundary")
    return serialized


def _canonical_gzip_bytes(tar_bytes: bytes) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(
        filename="", mode="wb", compresslevel=9, fileobj=output, mtime=0
    ) as compressed:
        compressed.write(tar_bytes)
    archive = output.getvalue()
    if len(archive) < 1 or len(archive) > MAX_ARCHIVE_BYTES:
        raise C0BError("compressed archive exceeds the transport boundary")
    return archive


def _decompress_archive(archive: bytes) -> bytes:
    if len(archive) < 18 or len(archive) > MAX_ARCHIVE_BYTES:
        raise C0BError("compressed archive size is outside the transport boundary")
    if (
        archive[:4] != b"\x1f\x8b\x08\x00"
        or archive[4:8] != b"\x00\x00\x00\x00"
        or archive[8:10] != b"\x02\xff"
    ):
        raise C0BError("gzip header is not canonical")
    decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        tar_bytes = decompressor.decompress(archive, MAX_TAR_BYTES + 1)
    except zlib.error as error:
        raise C0BError("gzip archive is invalid") from error
    if (
        len(tar_bytes) > MAX_TAR_BYTES
        or not decompressor.eof
        or decompressor.unconsumed_tail
        or decompressor.unused_data
    ):
        raise C0BError("gzip archive is truncated, concatenated, or oversized")
    return tar_bytes


def _payloads_from_tar(tar_bytes: bytes) -> dict[str, bytes]:
    try:
        with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:") as archive:
            members = archive.getmembers()
            if [member.name for member in members] != list(EXPECTED_RELATIVE_PATHS):
                raise C0BError("archive member set or order is not canonical")
            payloads: dict[str, bytes] = {}
            total = 0
            for member in members:
                if (
                    member.type != tarfile.REGTYPE
                    or not member.isreg()
                    or member.mode != 0o644
                    or member.uid != 0
                    or member.gid != 0
                    or member.mtime != 0
                    or member.uname
                    or member.gname
                    or member.linkname
                    or member.pax_headers
                    or getattr(member, "sparse", None)
                    or member.size < 1
                    or member.size > MAX_MEMBER_BYTES
                ):
                    raise C0BError("archive member metadata violates the boundary")
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise C0BError("archive member cannot be read")
                payload = extracted.read(MAX_MEMBER_BYTES + 1)
                if len(payload) != member.size:
                    raise C0BError("archive member size is inconsistent")
                total += len(payload)
                if total > MAX_PAYLOAD_BYTES:
                    raise C0BError("archive payload exceeds the transport boundary")
                payloads[member.name] = payload
    except (tarfile.TarError, OSError) as error:
        raise C0BError("tar archive is invalid") from error
    if _canonical_tar_bytes(payloads) != tar_bytes:
        raise C0BError("tar archive bytes are not canonical")
    return payloads


def _archive_payloads(archive: bytes) -> dict[str, bytes]:
    return _payloads_from_tar(_decompress_archive(archive))


def _write_exclusive(path: Path, payload: bytes, mode: int = 0o600) -> None:
    _require_new_path(path, "output file")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, mode)
    try:
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written < 1:
                raise C0BError("output file write did not make progress")
            view = view[written:]
        os.fchmod(descriptor, mode)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _extract_payloads_atomic(payloads: dict[str, bytes], output_dir: Path) -> None:
    if tuple(payloads) != EXPECTED_RELATIVE_PATHS:
        raise C0BError("extracted payload order is not canonical")
    _require_new_path(output_dir, "extraction directory")
    temporary = Path(
        tempfile.mkdtemp(prefix=f".{output_dir.name}.tmp-", dir=output_dir.parent)
    )
    try:
        os.chmod(temporary, 0o700)
        for directory_name in ("diff-items", "items"):
            (temporary / directory_name).mkdir(mode=0o700)
        for relative_path in EXPECTED_RELATIVE_PATHS:
            target = temporary / relative_path
            _write_exclusive(target, payloads[relative_path], mode=0o644)
        if _bundle_payloads(temporary) != payloads:
            raise C0BError("extracted payloads do not match the archive")
        os.replace(temporary, output_dir)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def _read_archive_file(archive_path: Path) -> bytes:
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(archive_path, flags)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise C0BError("archive must be a regular file")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            archive = source.read(MAX_ARCHIVE_BYTES + 1)
    finally:
        os.close(descriptor)
    if len(archive) != metadata.st_size:
        raise C0BError("archive size changed while it was read")
    return archive


def pack_bundle(evidence_dir: Path, archive_path: Path, roundtrip_dir: Path) -> None:
    _require_new_path(archive_path, "archive")
    _require_new_path(roundtrip_dir, "round-trip directory")
    source_payloads = _bundle_payloads(evidence_dir)
    archive = _canonical_gzip_bytes(_canonical_tar_bytes(source_payloads))
    try:
        _write_exclusive(archive_path, archive)
        restored_payloads = _archive_payloads(_read_archive_file(archive_path))
        if restored_payloads != source_payloads:
            raise C0BError("archive round trip changed evidence bytes")
        _extract_payloads_atomic(restored_payloads, roundtrip_dir)
        if _bundle_payloads(roundtrip_dir) != source_payloads:
            raise C0BError("round-trip directory changed evidence bytes")
    except BaseException:
        if archive_path.exists() and not archive_path.is_symlink():
            archive_path.unlink()
        if roundtrip_dir.exists() and not roundtrip_dir.is_symlink():
            shutil.rmtree(roundtrip_dir, ignore_errors=True)
        raise


def _split_encoded(encoded: str) -> tuple[str, ...]:
    if len(encoded) < 1 or len(encoded) > MAX_BASE64_CHARS:
        raise C0BError("base64 output size is outside the transport boundary")
    parts = tuple(
        encoded[index * OUTPUT_CHUNK_CHARS : (index + 1) * OUTPUT_CHUNK_CHARS]
        for index in range(OUTPUT_CHUNK_COUNT)
    )
    if "".join(parts) != encoded:
        raise C0BError("base64 output exceeds the fixed chunk boundary")
    return parts


def emit_outputs(archive_path: Path, github_output: Path) -> None:
    archive = _read_archive_file(archive_path)
    _archive_payloads(archive)
    encoded = base64.b64encode(archive).decode("ascii")
    parts = _split_encoded(encoded)
    lines = [
        *(f"archive_b64_{index}={part}\n" for index, part in enumerate(parts)),
        f"archive_sha256={hashlib.sha256(archive).hexdigest()}\n",
        f"archive_bytes={len(archive)}\n",
        f"archive_b64_chars={len(encoded)}\n",
    ]
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(github_output, flags, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise C0BError("GitHub output target must be a regular file")
        payload = "".join(lines).encode("ascii")
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written < 1:
                raise C0BError("GitHub output write did not make progress")
            view = view[written:]
    finally:
        os.close(descriptor)


def _required_environment(name: str) -> str:
    value = os.environ.get(name)
    if value is None:
        raise C0BError(f"required transport metadata is missing: {name}")
    return value


def _canonical_positive_integer(value: str, label: str, maximum: int) -> int:
    if POSITIVE_INTEGER.fullmatch(value) is None:
        raise C0BError(f"{label} is not a canonical positive integer")
    parsed = int(value)
    if parsed > maximum:
        raise C0BError(f"{label} exceeds the transport boundary")
    return parsed


def receive_outputs(archive_path: Path, output_dir: Path) -> None:
    parts = tuple(
        _required_environment(f"C0B_ARCHIVE_B64_{index}")
        for index in range(OUTPUT_CHUNK_COUNT)
    )
    encoded = "".join(parts)
    expected_chars = _canonical_positive_integer(
        _required_environment("C0B_ARCHIVE_B64_CHARS"),
        "base64 character count",
        MAX_BASE64_CHARS,
    )
    if (
        len(encoded) != expected_chars
        or len(encoded) % 4 != 0
        or BASE64_TEXT.fullmatch(encoded) is None
        or parts != _split_encoded(encoded)
    ):
        raise C0BError("base64 job outputs are missing, reordered, or malformed")
    try:
        archive = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise C0BError("base64 job outputs are invalid") from error
    if base64.b64encode(archive).decode("ascii") != encoded:
        raise C0BError("base64 job outputs are not canonical")
    expected_bytes = _canonical_positive_integer(
        _required_environment("C0B_ARCHIVE_BYTES"),
        "archive byte count",
        MAX_ARCHIVE_BYTES,
    )
    expected_sha = _required_environment("C0B_ARCHIVE_SHA256")
    if (
        len(archive) != expected_bytes
        or HEX64.fullmatch(expected_sha) is None
        or hashlib.sha256(archive).hexdigest() != expected_sha
    ):
        raise C0BError("archive size or checksum does not match job outputs")
    payloads = _archive_payloads(archive)
    _require_new_path(archive_path, "received archive")
    _require_new_path(output_dir, "received evidence directory")
    try:
        _write_exclusive(archive_path, archive)
        _extract_payloads_atomic(payloads, output_dir)
    except BaseException:
        if archive_path.exists() and not archive_path.is_symlink():
            archive_path.unlink()
        if output_dir.exists() and not output_dir.is_symlink():
            shutil.rmtree(output_dir, ignore_errors=True)
        raise


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Transport only the final sanitized C0-B JSON bundle."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    pack = commands.add_parser("pack", help="pack and round-trip the final bundle")
    pack.add_argument("--evidence-dir", required=True, type=Path)
    pack.add_argument("--archive", required=True, type=Path)
    pack.add_argument("--roundtrip-dir", required=True, type=Path)

    emit = commands.add_parser("emit", help="emit four bounded GitHub job outputs")
    emit.add_argument("--archive", required=True, type=Path)
    emit.add_argument("--github-output", required=True, type=Path)

    receive = commands.add_parser(
        "receive", help="validate and restore the four GitHub job outputs"
    )
    receive.add_argument("--archive", required=True, type=Path)
    receive.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    try:
        if arguments.command == "pack":
            pack_bundle(
                arguments.evidence_dir, arguments.archive, arguments.roundtrip_dir
            )
            message = "Packed and round-tripped the sanitized C0-B bundle."
        elif arguments.command == "emit":
            emit_outputs(arguments.archive, arguments.github_output)
            message = "Emitted bounded sanitized C0-B job outputs."
        elif arguments.command == "receive":
            receive_outputs(arguments.archive, arguments.output_dir)
            message = "Restored the sanitized C0-B job outputs."
        else:
            raise C0BError("unsupported transport command")
    except (C0BError, OSError) as error:
        print(f"C0-B transport failed: {error}", file=sys.stderr)
        return 1
    print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
