from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from verify_telemetry import (
    GENESIS_HASH,
    TelemetryVerificationError,
    record_hash,
    verify_telemetry,
)


class VerifyTelemetryTests(unittest.TestCase):
    def make_log(self, count: int = 3) -> Path:
        root = Path(tempfile.mkdtemp(prefix="firsttake-telemetry-"))
        path = root / "probe-telemetry.jsonl"
        previous = GENESIS_HASH
        with path.open("w", encoding="utf-8", newline="\n") as stream:
            for sequence in range(count):
                payload = json.dumps(
                    {"type": "SAMPLE", "value": sequence},
                    separators=(",", ":"),
                )
                digest = record_hash(sequence, previous, payload)
                stream.write(
                    json.dumps(
                        {
                            "schemaVersion": (
                                "firsttake.telemetry-envelope.v1"
                            ),
                            "sequence": sequence,
                            "previousHash": previous,
                            "payloadJson": payload,
                            "hash": digest,
                        },
                        separators=(",", ":"),
                    )
                    + "\n",
                )
                previous = digest
        return path

    def test_verifies_complete_chain(self) -> None:
        report = verify_telemetry(self.make_log())
        self.assertEqual("VALID_PREFIX", report["status"])
        self.assertEqual(3, report["records"])
        self.assertEqual({"SAMPLE": 3}, report["payloadTypes"])

    def test_detects_payload_tampering(self) -> None:
        path = self.make_log()
        lines = path.read_text(encoding="utf-8").splitlines()
        envelope = json.loads(lines[1])
        envelope["payloadJson"] = '{"type":"SAMPLE","value":9}'
        lines[1] = json.dumps(envelope, separators=(",", ":"))
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(
            TelemetryVerificationError,
            "hash mismatch",
        ):
            verify_telemetry(path)

    def test_accepts_only_verified_prefix_after_torn_tail(self) -> None:
        path = self.make_log(2)
        with path.open("ab") as stream:
            stream.write(b'{"schemaVersion":"firsttake.tele')
        report = verify_telemetry(path)
        self.assertEqual(2, report["records"])
        self.assertGreater(report["ignoredTornTailBytes"], 0)

    def test_rejects_invalid_complete_line(self) -> None:
        path = self.make_log(1)
        with path.open("ab") as stream:
            stream.write(b"not-json\n")
        with self.assertRaisesRegex(
            TelemetryVerificationError,
            "invalid envelope",
        ):
            verify_telemetry(path)


if __name__ == "__main__":
    unittest.main()
