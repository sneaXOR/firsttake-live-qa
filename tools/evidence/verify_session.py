"""Verify an untouched FirstTake source session on a PC."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from verify_telemetry import verify_telemetry


WAL_SCHEMA = "firsttake.session.wal.v1"
WAL_GENESIS_HASH = "GENESIS"
EVENT_TYPE_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class SessionVerificationError(RuntimeError):
    pass


def decode_base64url(value: str) -> str:
    padded = value + "=" * (-len(value) % 4)
    try:
        return base64.urlsafe_b64decode(padded).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as error:
        raise SessionVerificationError("invalid WAL payload encoding") from error


def verify_wal(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    lines = raw.splitlines(keepends=True)
    ignored_torn_tail_bytes = 0
    if raw and not raw.endswith((b"\n", b"\r")):
        ignored_torn_tail_bytes = len(lines[-1])
        lines = lines[:-1]

    previous_hash = WAL_GENESIS_HASH
    records: list[dict[str, Any]] = []
    for sequence, raw_line in enumerate(lines):
        try:
            line = raw_line.decode("utf-8").rstrip("\r\n")
        except UnicodeDecodeError as error:
            raise SessionVerificationError(
                f"invalid WAL UTF-8 at sequence {sequence}",
            ) from error
        fields = line.split("|")
        if len(fields) != 7:
            raise SessionVerificationError(
                f"invalid WAL field count at sequence {sequence}",
            )
        (
            schema,
            sequence_text,
            elapsed_text,
            event_type,
            payload_base64,
            declared_previous,
            declared_hash,
        ) = fields
        if schema != WAL_SCHEMA:
            raise SessionVerificationError(
                f"unsupported WAL schema at sequence {sequence}",
            )
        try:
            declared_sequence = int(sequence_text)
            elapsed_ns = int(elapsed_text)
        except ValueError as error:
            raise SessionVerificationError(
                f"invalid WAL numeric field at sequence {sequence}",
            ) from error
        if declared_sequence != sequence or elapsed_ns < 0:
            raise SessionVerificationError(
                f"invalid WAL sequence/timestamp at sequence {sequence}",
            )
        if EVENT_TYPE_RE.fullmatch(event_type) is None:
            raise SessionVerificationError(
                f"invalid WAL event type at sequence {sequence}",
            )
        if declared_previous != previous_hash:
            raise SessionVerificationError(
                f"WAL previous hash mismatch at sequence {sequence}",
            )
        if SHA256_RE.fullmatch(declared_hash) is None:
            raise SessionVerificationError(
                f"invalid WAL hash at sequence {sequence}",
            )
        canonical = "|".join(fields[:6])
        actual_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        if declared_hash != actual_hash:
            raise SessionVerificationError(
                f"WAL content hash mismatch at sequence {sequence}",
            )
        payload_text = decode_base64url(payload_base64)
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError as error:
            raise SessionVerificationError(
                f"invalid WAL JSON payload at sequence {sequence}",
            ) from error
        records.append(
            {
                "sequence": sequence,
                "elapsedRealtimeNs": elapsed_ns,
                "type": event_type,
                "payload": payload,
                "hash": declared_hash,
            },
        )
        previous_hash = declared_hash
    return {
        "records": records,
        "lastHash": previous_hash,
        "ignoredTornTailBytes": ignored_torn_tail_bytes,
    }


def inspect_mp4(path: Path) -> dict[str, Any] | None:
    ffprobe = shutil.which("ffprobe")
    if ffprobe is None:
        return None
    command = [
        ffprobe,
        "-v",
        "error",
        "-show_entries",
        "format=duration,size:stream=codec_type,codec_name,width,height",
        "-of",
        "json",
        str(path),
    ]
    completed = subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if completed.returncode != 0:
        raise SessionVerificationError(
            f"ffprobe rejected capture.mp4: {completed.stderr.strip()}",
        )
    return json.loads(completed.stdout)


def inspect_mcap(path: Path) -> dict[str, Any]:
    try:
        import mcap
        from mcap.reader import make_reader
    except ImportError as error:
        raise SessionVerificationError(
            "the official mcap Python package is required",
        ) from error
    topics: dict[str, int] = {}
    try:
        with path.open("rb") as stream:
            reader = make_reader(stream)
            header = reader.get_header()
            for _, channel, message in reader.iter_messages(
                log_time_order=False,
            ):
                json.loads(message.data)
                topics[channel.topic] = topics.get(channel.topic, 0) + 1
    except Exception as error:
        raise SessionVerificationError(
            f"official reader rejected {path.name}: {error}",
        ) from error
    return {
        "reader": f"mcap-python/{mcap.__version__}",
        "library": header.library,
        "topics": dict(sorted(topics.items())),
        "messages": sum(topics.values()),
    }


def select_artifact(session: Path, *names: str) -> Path:
    for name in names:
        candidate = session / name
        if candidate.is_file():
            return candidate
    raise SessionVerificationError(
        f"required artifact missing: {' or '.join(names)}",
    )


def verify_published_hashes(session: Path) -> dict[str, str] | None:
    path = session / "hashes.sha256"
    if not path.exists():
        return None
    declared: dict[str, str] = {}
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        fields = line.split("  ", maxsplit=1)
        if (
            len(fields) != 2
            or SHA256_RE.fullmatch(fields[0]) is None
            or not fields[1]
            or Path(fields[1]).name != fields[1]
        ):
            raise SessionVerificationError(
                f"invalid hashes.sha256 line {line_number}",
            )
        if fields[1] in declared:
            raise SessionVerificationError(
                f"duplicate hashes.sha256 entry: {fields[1]}",
            )
        declared[fields[1]] = fields[0]
    for name, expected in declared.items():
        artifact = session / name
        if not artifact.is_file():
            raise SessionVerificationError(
                f"hashed artifact missing: {name}",
            )
        actual = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if actual != expected:
            raise SessionVerificationError(
                f"published SHA-256 mismatch: {name}",
            )
    return declared


def verify_session(session: Path) -> dict[str, Any]:
    session = session.resolve()
    if not session.is_dir():
        raise SessionVerificationError(f"session not found: {session}")
    required: dict[str, Path] = {
        "session.wal": session / "session.wal",
        "capture.mp4": session / "capture.mp4",
        "mcap": select_artifact(session, "session.mcap", "imu.mcap"),
        "telemetry": select_artifact(
            session,
            "qa-events.jsonl",
            "probe-telemetry.jsonl",
        ),
    }
    for name, path in required.items():
        if not path.is_file():
            raise SessionVerificationError(f"required artifact missing: {name}")

    wal = verify_wal(required["session.wal"])
    telemetry = verify_telemetry(required["telemetry"])
    anchors = [
        record
        for record in wal["records"]
        if record["type"] == "TELEMETRY_FINALIZED"
    ]
    telemetry_anchored = False
    if anchors:
        anchor = anchors[-1]["payload"]
        if (
            anchor.get("lastHash") != telemetry["lastHash"]
            or anchor.get("writtenRecords") != telemetry["records"]
        ):
            raise SessionVerificationError(
                "telemetry chain does not match its WAL anchor",
            )
        telemetry_anchored = anchor.get("complete") is True

    committed = any(
        record["type"] == "SESSION_COMMITTED"
        for record in wal["records"]
    )
    if committed and not telemetry_anchored:
        raise SessionVerificationError(
            "committed session has no complete telemetry WAL anchor",
        )

    hashes = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for name, path in required.items()
    }
    published_hashes = verify_published_hashes(session)
    manifest = None
    postflight = None
    if (session / "manifest.json").is_file():
        manifest = json.loads(
            (session / "manifest.json").read_text(encoding="utf-8"),
        )
    if (session / "postflight.json").is_file():
        postflight = json.loads(
            (session / "postflight.json").read_text(encoding="utf-8"),
        )
    acceptance_status = (
        postflight.get("verdict", "NOT_ASSESSED")
        if isinstance(postflight, dict)
        else "NOT_ASSESSED"
    )
    return {
        "schemaVersion": "firsttake.session-verification.v1",
        "status": (
            "VALID_COMMITTED"
            if committed
            else "VALID_INTERRUPTED_PREFIX"
        ),
        "acceptanceStatus": acceptance_status,
        "session": str(session),
        "walRecords": len(wal["records"]),
        "walIgnoredTornTailBytes": wal["ignoredTornTailBytes"],
        "telemetry": telemetry,
        "telemetryAnchoredInWal": telemetry_anchored,
        "mcap": inspect_mcap(required["mcap"]),
        "mp4": inspect_mp4(required["capture.mp4"]),
        "sha256": hashes,
        "publishedHashesVerified": published_hashes is not None,
        "publishedHashCount": (
            len(published_hashes)
            if published_hashes is not None
            else 0
        ),
        "manifest": manifest,
        "postflight": postflight,
        "limitations": [
            "Integrity is not proof that the filmed task was semantically correct.",
            (
                "MP4 codec inspection unavailable because ffprobe is absent."
                if shutil.which("ffprobe") is None
                else "MP4 container was independently accepted by ffprobe."
            ),
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path)
    parser.add_argument(
        "--require-pass",
        action="store_true",
        help="return non-zero unless the Android postflight verdict is PASS",
    )
    args = parser.parse_args()
    try:
        report = verify_session(args.session)
    except (
        OSError,
        SessionVerificationError,
    ) as error:
        print(
            json.dumps(
                {
                    "schemaVersion": "firsttake.session-verification.v1",
                    "status": "INVALID",
                    "error": str(error),
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    if args.require_pass and report["acceptanceStatus"] != "PASS":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
