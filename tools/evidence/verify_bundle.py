"""Independently verify a FirstTake recovery evidence bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SOURCE_HASH_RE = re.compile(
    r"^(?P<hash>[0-9a-f]{64})  (?P<session>[^/\\]+)/(?P<name>[^/\\]+)$",
)
REQUIRED_BUNDLE_FILES = {
    "recovery-report.json",
    "device.json",
    "source-hashes.sha256",
}


class VerificationError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"invalid JSON {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise VerificationError(f"{path.name} must contain a JSON object")
    return value


def safe_bundle_member(bundle: Path, relative_name: str) -> Path:
    if (
        not relative_name
        or Path(relative_name).name != relative_name
        or relative_name in {".", ".."}
    ):
        raise VerificationError(
            f"unsafe bundle manifest path: {relative_name!r}",
        )
    member = bundle / relative_name
    if not member.is_file():
        raise VerificationError(f"bundle artifact missing: {relative_name}")
    return member


def parse_source_hashes(path: Path) -> dict[tuple[str, str], str]:
    entries: dict[tuple[str, str], str] = {}
    for line_number, raw_line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not raw_line:
            continue
        match = SOURCE_HASH_RE.fullmatch(raw_line)
        if match is None:
            raise VerificationError(
                f"malformed source hash line {line_number}",
            )
        key = (match["session"], match["name"])
        if key in entries:
            raise VerificationError(f"duplicate source hash entry: {key}")
        entries[key] = match["hash"]
    return entries


def expected_source_hashes(
    report: dict[str, Any],
) -> dict[tuple[str, str], str]:
    try:
        session_id = report["sessionId"]
        artifacts = [
            report["wal"]["artifact"],
            report["video"]["artifact"],
            report["imu"]["artifact"],
        ]
        telemetry = report.get("telemetry")
        if isinstance(telemetry, dict) and "artifact" in telemetry:
            artifacts.append(telemetry["artifact"])
    except (KeyError, TypeError) as error:
        raise VerificationError(
            f"recovery report is missing artifact evidence: {error}",
        ) from error
    if not isinstance(session_id, str) or not session_id:
        raise VerificationError("recovery report has invalid sessionId")

    expected: dict[tuple[str, str], str] = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise VerificationError("artifact evidence must be an object")
        if artifact.get("present") is not True:
            continue
        name = artifact.get("name")
        digest = artifact.get("sha256")
        if not isinstance(name, str) or Path(name).name != name:
            raise VerificationError(f"unsafe source artifact name: {name!r}")
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
            raise VerificationError(f"invalid source hash for {name}")
        if artifact.get("hashState") != "COMPUTED":
            raise VerificationError(f"source hash not computed for {name}")
        expected[(session_id, name)] = digest
    return expected


def validate_recovered_mcap(path: Path) -> dict[str, Any]:
    try:
        import mcap
        from mcap.reader import make_reader
    except ImportError as error:
        raise VerificationError(
            "the official mcap Python package is required to verify "
            "recovered-imu.mcap",
        ) from error

    topics: dict[str, int] = {}
    messages = 0
    try:
        with path.open("rb") as stream:
            reader = make_reader(stream)
            header = reader.get_header()
            for _, channel, message in reader.iter_messages(
                log_time_order=False,
            ):
                json.loads(message.data)
                topics[channel.topic] = topics.get(channel.topic, 0) + 1
                messages += 1
    except Exception as error:
        raise VerificationError(f"invalid recovered MCAP: {error}") from error
    allowed_topics = {
        "/firsttake/camera_analysis_frame",
        "/firsttake/camera_capture_result",
        "/firsttake/capture_event",
        "/firsttake/clock_anchor",
        "/imu/accelerometer",
        "/imu/gyroscope",
    }
    if not set(topics).issubset(allowed_topics):
        raise VerificationError(
            f"unexpected recovered MCAP topics: {sorted(topics)}",
        )
    return {
        "reader": f"mcap-python/{mcap.__version__}",
        "library": header.library,
        "messages": messages,
        "topics": dict(sorted(topics.items())),
    }


def verify_bundle(
    bundle: Path,
    source_session: Path | None = None,
) -> dict[str, Any]:
    bundle = bundle.resolve()
    if not bundle.is_dir():
        raise VerificationError(f"bundle directory not found: {bundle}")

    manifest = load_json(bundle / "bundle-manifest.json")
    if manifest.get("schemaVersion") != "firsttake.evidence-bundle.v1":
        raise VerificationError("unsupported evidence bundle schema")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise VerificationError("manifest files must be an object")
    if not REQUIRED_BUNDLE_FILES.issubset(files):
        missing = sorted(REQUIRED_BUNDLE_FILES - set(files))
        raise VerificationError(f"manifest is missing required files: {missing}")

    verified_bundle_hashes: dict[str, str] = {}
    for relative_name, expected_hash in files.items():
        if (
            not isinstance(relative_name, str)
            or not isinstance(expected_hash, str)
            or SHA256_RE.fullmatch(expected_hash) is None
        ):
            raise VerificationError("manifest contains an invalid hash entry")
        member = safe_bundle_member(bundle, relative_name)
        actual_hash = sha256(member)
        if actual_hash != expected_hash:
            raise VerificationError(
                f"bundle hash mismatch for {relative_name}",
            )
        verified_bundle_hashes[relative_name] = actual_hash

    report = load_json(bundle / "recovery-report.json")
    device = load_json(bundle / "device.json")
    if report.get("schemaVersion") != "firsttake.recovery.v1":
        raise VerificationError("unsupported recovery report schema")
    if device.get("schemaVersion") != "firsttake.device-evidence.v1":
        raise VerificationError("unsupported device report schema")

    declared_sources = parse_source_hashes(bundle / "source-hashes.sha256")
    expected_sources = expected_source_hashes(report)
    if declared_sources != expected_sources:
        raise VerificationError(
            "source-hashes.sha256 does not match recovery-report.json",
        )

    source_files_verified = False
    if source_session is not None:
        source_root = source_session.resolve()
        if not source_root.is_dir():
            raise VerificationError(
                f"source session directory not found: {source_root}",
            )
        for (_, name), expected_hash in expected_sources.items():
            source_file = source_root / name
            if not source_file.is_file():
                raise VerificationError(f"source artifact missing: {name}")
            if sha256(source_file) != expected_hash:
                raise VerificationError(f"source hash mismatch for {name}")
        source_files_verified = True

    recovered_mcap = None
    if "recovered-imu.mcap" in verified_bundle_hashes:
        recovered_mcap = validate_recovered_mcap(
            bundle / "recovered-imu.mcap",
        )

    return {
        "schemaVersion": "firsttake.bundle-verification.v1",
        "status": "VALID",
        "bundle": str(bundle),
        "sessionId": report["sessionId"],
        "recoveryState": report["state"],
        "bundleFilesVerified": len(verified_bundle_hashes),
        "declaredSourceHashesVerified": len(expected_sources),
        "sourceFilesVerified": source_files_verified,
        "recoveredMcap": recovered_mcap,
        "limitations": [
            (
                "Bundle integrity does not prove the original capture was "
                "high quality."
            ),
            (
                "Source bytes were not independently checked."
                if not source_files_verified
                else "Source bytes match the hashes declared by the device."
            ),
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--source-session", type=Path)
    args = parser.parse_args()
    try:
        result = verify_bundle(args.bundle, args.source_session)
    except VerificationError as error:
        print(
            json.dumps(
                {
                    "schemaVersion": "firsttake.bundle-verification.v1",
                    "status": "INVALID",
                    "error": str(error),
                },
                indent=2,
                sort_keys=True,
            ),
        )
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
