from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from verify_bundle import VerificationError, verify_bundle


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class VerifyBundleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.bundle = root / "bundle"
        self.source = root / "source"
        self.bundle.mkdir()
        self.source.mkdir()
        self.session_id = "session-123"

        artifacts = {
            "session.wal": b"wal",
            "capture.mp4": b"video",
            "imu.mcap": b"imu",
        }
        for name, data in artifacts.items():
            (self.source / name).write_bytes(data)
        report = {
            "schemaVersion": "firsttake.recovery.v1",
            "sessionId": self.session_id,
            "state": "INTERRUPTED_RECOVERABLE",
            "wal": {"artifact": self.artifact("session.wal", b"wal")},
            "video": {"artifact": self.artifact("capture.mp4", b"video")},
            "imu": {"artifact": self.artifact("imu.mcap", b"imu")},
        }
        device = {
            "schemaVersion": "firsttake.device-evidence.v1",
            "manufacturer": "test",
        }
        (self.bundle / "recovery-report.json").write_text(
            json.dumps(report),
            encoding="utf-8",
        )
        (self.bundle / "device.json").write_text(
            json.dumps(device),
            encoding="utf-8",
        )
        source_lines = "".join(
            f"{digest(data)}  {self.session_id}/{name}\n"
            for name, data in artifacts.items()
        )
        (self.bundle / "source-hashes.sha256").write_text(
            source_lines,
            encoding="utf-8",
        )
        files = {
            name: digest((self.bundle / name).read_bytes())
            for name in (
                "recovery-report.json",
                "device.json",
                "source-hashes.sha256",
            )
        }
        (self.bundle / "bundle-manifest.json").write_text(
            json.dumps(
                {
                    "schemaVersion": "firsttake.evidence-bundle.v1",
                    "files": files,
                },
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def artifact(name: str, data: bytes) -> dict[str, object]:
        return {
            "name": name,
            "present": True,
            "bytes": len(data),
            "sha256": digest(data),
            "hashState": "COMPUTED",
            "error": None,
        }

    def test_verifies_bundle_and_original_source_bytes(self) -> None:
        result = verify_bundle(self.bundle, self.source)
        self.assertEqual("VALID", result["status"])
        self.assertTrue(result["sourceFilesVerified"])
        self.assertEqual(3, result["bundleFilesVerified"])

    def test_detects_bundle_tampering(self) -> None:
        with (self.bundle / "device.json").open("a", encoding="utf-8") as file:
            file.write(" ")
        with self.assertRaisesRegex(VerificationError, "hash mismatch"):
            verify_bundle(self.bundle)

    def test_detects_source_tampering_when_source_is_available(self) -> None:
        (self.source / "capture.mp4").write_bytes(b"tampered")
        with self.assertRaisesRegex(VerificationError, "source hash mismatch"):
            verify_bundle(self.bundle, self.source)

    def test_rejects_manifest_path_traversal(self) -> None:
        manifest_path = self.bundle / "bundle-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"]["../outside"] = "0" * 64
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(VerificationError, "unsafe"):
            verify_bundle(self.bundle)


if __name__ == "__main__":
    unittest.main()
