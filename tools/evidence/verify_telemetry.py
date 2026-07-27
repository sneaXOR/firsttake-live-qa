"""Verify the hash-chained prefix of Android FirstTake telemetry."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


GENESIS_HASH = "0" * 64


class TelemetryVerificationError(RuntimeError):
    pass


def record_hash(sequence: int, previous_hash: str, payload: str) -> str:
    material = f"{sequence}\n{previous_hash}\n{payload}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def verify_telemetry(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    lines = raw.splitlines(keepends=True)
    ignored_torn_tail_bytes = 0
    if raw and not raw.endswith((b"\n", b"\r")):
        ignored_torn_tail_bytes = len(lines[-1])
        lines = lines[:-1]

    previous_hash = GENESIS_HASH
    payload_types: dict[str, int] = {}
    for expected_sequence, raw_line in enumerate(lines):
        try:
            envelope = json.loads(raw_line)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise TelemetryVerificationError(
                f"invalid envelope at sequence {expected_sequence}: {error}",
            ) from error
        if envelope.get("schemaVersion") != (
            "firsttake.telemetry-envelope.v1"
        ):
            raise TelemetryVerificationError(
                f"unsupported envelope at sequence {expected_sequence}",
            )
        if envelope.get("sequence") != expected_sequence:
            raise TelemetryVerificationError(
                f"non-contiguous sequence at {expected_sequence}",
            )
        if envelope.get("previousHash") != previous_hash:
            raise TelemetryVerificationError(
                f"previous hash mismatch at sequence {expected_sequence}",
            )
        payload = envelope.get("payloadJson")
        if not isinstance(payload, str):
            raise TelemetryVerificationError(
                f"payload is not a string at sequence {expected_sequence}",
            )
        expected_hash = record_hash(
            expected_sequence,
            previous_hash,
            payload,
        )
        if envelope.get("hash") != expected_hash:
            raise TelemetryVerificationError(
                f"record hash mismatch at sequence {expected_sequence}",
            )
        try:
            payload_object = json.loads(payload)
        except json.JSONDecodeError as error:
            raise TelemetryVerificationError(
                f"invalid payload at sequence {expected_sequence}: {error}",
            ) from error
        payload_type = payload_object.get("type", "UNKNOWN")
        payload_types[payload_type] = payload_types.get(payload_type, 0) + 1
        previous_hash = expected_hash

    return {
        "schemaVersion": "firsttake.telemetry-verification.v1",
        "status": "VALID_PREFIX",
        "records": len(lines),
        "lastHash": previous_hash,
        "ignoredTornTailBytes": ignored_torn_tail_bytes,
        "payloadTypes": dict(sorted(payload_types.items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("telemetry", type=Path)
    args = parser.parse_args()
    try:
        report = verify_telemetry(args.telemetry)
    except (OSError, TelemetryVerificationError) as error:
        print(
            json.dumps(
                {
                    "schemaVersion": "firsttake.telemetry-verification.v1",
                    "status": "INVALID",
                    "error": str(error),
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
