from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from verify_session import (
    SessionVerificationError,
    verify_session,
    verify_wal,
)
from verify_telemetry import GENESIS_HASH, record_hash


class VerifySessionTests(unittest.TestCase):
    def make_wal(
        self,
        path: Path,
        events: list[tuple[str, dict[str, object]]],
    ) -> None:
        previous = "GENESIS"
        lines = []
        for sequence, (event_type, payload) in enumerate(events):
            encoded = base64.urlsafe_b64encode(
                json.dumps(payload, separators=(",", ":")).encode(),
            ).decode().rstrip("=")
            canonical = "|".join(
                [
                    "firsttake.session.wal.v1",
                    str(sequence),
                    str(100 + sequence),
                    event_type,
                    encoded,
                    previous,
                ],
            )
            digest = hashlib.sha256(canonical.encode()).hexdigest()
            lines.append(f"{canonical}|{digest}\n")
            previous = digest
        path.write_text("".join(lines), encoding="utf-8")

    def make_telemetry(self, path: Path) -> tuple[int, str]:
        previous = GENESIS_HASH
        lines = []
        for sequence in range(2):
            payload = json.dumps(
                {"type": "SAMPLE", "value": sequence},
                separators=(",", ":"),
            )
            digest = record_hash(sequence, previous, payload)
            lines.append(
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
        path.write_text("".join(lines), encoding="utf-8")
        return 2, previous

    def test_wal_accepts_only_complete_verified_prefix(self) -> None:
        root = Path(tempfile.mkdtemp())
        path = root / "session.wal"
        self.make_wal(path, [("SESSION_OPENED", {})])
        with path.open("ab") as stream:
            stream.write(b"firsttake.session.wal.v1|1|")
        report = verify_wal(path)
        self.assertEqual(1, len(report["records"]))
        self.assertGreater(report["ignoredTornTailBytes"], 0)

    @patch("verify_session.inspect_mp4", return_value={"format": {}})
    @patch(
        "verify_session.inspect_mcap",
        return_value={"reader": "official", "messages": 2},
    )
    def test_committed_session_requires_matching_telemetry_anchor(
        self,
        _mcap: object,
        _mp4: object,
    ) -> None:
        root = Path(tempfile.mkdtemp())
        for name in ("capture.mp4", "imu.mcap"):
            (root / name).write_bytes(b"fixture")
        records, last_hash = self.make_telemetry(
            root / "probe-telemetry.jsonl",
        )
        self.make_wal(
            root / "session.wal",
            [
                ("SESSION_OPENED", {}),
                (
                    "TELEMETRY_FINALIZED",
                    {
                        "writtenRecords": records,
                        "lastHash": last_hash,
                        "complete": True,
                    },
                ),
                ("SESSION_COMMITTED", {}),
            ],
        )
        report = verify_session(root)
        self.assertEqual("VALID_COMMITTED", report["status"])
        self.assertEqual("NOT_ASSESSED", report["acceptanceStatus"])
        self.assertTrue(report["telemetryAnchoredInWal"])

    @patch("verify_session.inspect_mp4", return_value={"format": {}})
    @patch(
        "verify_session.inspect_mcap",
        return_value={"reader": "official", "messages": 2},
    )
    def test_anchor_mismatch_is_rejected(
        self,
        _mcap: object,
        _mp4: object,
    ) -> None:
        root = Path(tempfile.mkdtemp())
        for name in ("capture.mp4", "imu.mcap"):
            (root / name).write_bytes(b"fixture")
        records, _ = self.make_telemetry(root / "probe-telemetry.jsonl")
        self.make_wal(
            root / "session.wal",
            [
                (
                    "TELEMETRY_FINALIZED",
                    {
                        "writtenRecords": records,
                        "lastHash": "0" * 64,
                        "complete": True,
                    },
                ),
                ("SESSION_COMMITTED", {}),
            ],
        )
        with self.assertRaisesRegex(
            SessionVerificationError,
            "does not match",
        ):
            verify_session(root)


if __name__ == "__main__":
    unittest.main()
