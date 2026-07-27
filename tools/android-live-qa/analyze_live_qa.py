"""Analyze controlled real-camera FirstTake live-QA trials."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

EVIDENCE_TOOLS = Path(__file__).resolve().parents[1] / "evidence"
sys.path.insert(0, str(EVIDENCE_TOOLS))
from verify_session import verify_session  # noqa: E402


def payloads(path: Path) -> list[dict[str, Any]]:
    return [
        json.loads(json.loads(line)["payloadJson"])
        for line in path.read_text(encoding="utf-8").splitlines()
    ]


def analyze_run(root: Path) -> dict[str, Any]:
    metadata = json.loads(
        (root / "run.json").read_text(encoding="utf-8-sig"),
    )
    sessions = list((root / "source").rglob("session.wal"))
    if len(sessions) != 1:
        raise RuntimeError(f"expected one source session under {root}")
    session = sessions[0].parent
    verification = verify_session(session)
    events = payloads(session / "qa-events.jsonl")
    probes = [
        event
        for event in events
        if event.get("type") == "CONTROLLED_EXPOSURE_PROBE"
        and event.get("applied") is True
    ]
    transitions = [
        event
        for event in events
        if event.get("type") == "QUALITY_TRANSITION"
        and event.get("defect") == "OVEREXPOSED"
    ]
    alerts = [
        event
        for event in transitions
        if event.get("kind") == "ALERT"
    ]
    recoveries = [
        event
        for event in transitions
        if event.get("kind") == "RECOVERED"
    ]
    maximum = next(
        (event for event in probes if event.get("level") == "MAXIMUM"),
        None,
    )
    analysis_samples = [
        event
        for event in events
        if event.get("type") == "ANALYSIS_SAMPLE"
    ]
    nominal_after_maximum = next(
        (
            event
            for event in probes
            if event.get("level") == "NOMINAL"
            and maximum is not None
            and event["elapsedRealtimeNs"] > maximum["elapsedRealtimeNs"]
        ),
        None,
    )
    control_to_alert_latency_ms = None
    if maximum is not None and alerts:
        control_to_alert_latency_ms = (
            alerts[0]["elapsedRealtimeNs"]
            - maximum["elapsedRealtimeNs"]
        ) / 1_000_000.0
    sensor_defect_latency_ms = None
    defect_onset_ns = None
    if alerts:
        evidence_before_alert = [
            event
            for event in analysis_samples
            if event["acceptedAtNs"] <= alerts[0]["elapsedRealtimeNs"]
        ]
        for event in reversed(evidence_before_alert):
            if (
                event["qualityAssessments"].get("OVEREXPOSED")
                != "DETECTED"
            ):
                break
            defect_onset_ns = event["acceptedAtNs"]
        if defect_onset_ns is not None:
            sensor_defect_latency_ms = (
                alerts[0]["elapsedRealtimeNs"] - defect_onset_ns
            ) / 1_000_000.0
    recovery_ms = None
    if nominal_after_maximum is not None and recoveries:
        recovery_ms = (
            recoveries[0]["elapsedRealtimeNs"]
            - nominal_after_maximum["elapsedRealtimeNs"]
        ) / 1_000_000.0
    postflight = json.loads(
        (session / "postflight.json").read_text(encoding="utf-8"),
    )
    expected_alert = metadata["kind"] == "persistent-bright"
    passed = (
        verification["status"] == "VALID_COMMITTED"
        and postflight["verdict"] == "PASS"
        and postflight["video"]["largeGapCount"] == 0
        and (
            (
                expected_alert
                and len(alerts) == 1
                and sensor_defect_latency_ms is not None
                and sensor_defect_latency_ms <= 3_000
                and len(recoveries) == 1
            )
            or (not expected_alert and len(alerts) == 0)
        )
    )
    return {
        "run": root.name,
        "kind": metadata["kind"],
        "sessionId": metadata["sessionId"],
        "sessionVerification": verification["status"],
        "postflightVerdict": postflight["verdict"],
        "videoSamples": postflight["video"]["sampleCount"],
        "videoP95DeltaUs": postflight["video"]["p95DeltaUs"],
        "videoMaximumDeltaUs": postflight["video"]["maximumDeltaUs"],
        "videoLargeGaps": postflight["video"]["largeGapCount"],
        "alerts": len(alerts),
        "recoveries": len(recoveries),
        "sensorDefectOnsetNs": defect_onset_ns,
        "sensorDefectToAlertLatencyMs": sensor_defect_latency_ms,
        "controlAppliedToAlertLatencyMs": control_to_alert_latency_ms,
        "recoveryLatencyMs": recovery_ms,
        "passed": passed,
    }


def percentile95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(
        0,
        min(len(ordered) - 1, int(len(ordered) * 0.95 + 0.999) - 1),
    )
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("campaign", type=Path)
    args = parser.parse_args()
    roots = sorted(
        root
        for root in args.campaign.glob("run-*")
        if (root / "run.json").is_file()
    )
    runs = [analyze_run(root) for root in roots]
    persistent = [run for run in runs if run["kind"] == "persistent-bright"]
    transient = [run for run in runs if run["kind"] == "transient-bright"]
    sensor_alert_latencies = [
        run["sensorDefectToAlertLatencyMs"]
        for run in persistent
        if run["sensorDefectToAlertLatencyMs"] is not None
    ]
    control_alert_latencies = [
        run["controlAppliedToAlertLatencyMs"]
        for run in persistent
        if run["controlAppliedToAlertLatencyMs"] is not None
    ]
    report = {
        "schemaVersion": "firsttake.live-qa-campaign.v1",
        "verdict": (
            "PASS"
            if (
                len(persistent) >= 6
                and len(transient) >= 6
                and all(run["passed"] for run in runs)
            )
            else "INCOMPLETE_OR_FAILED"
        ),
        "persistent": {
            "runs": len(persistent),
            "detected": sum(run["alerts"] == 1 for run in persistent),
            "p95SensorDefectToAlertLatencyMs":
                percentile95(sensor_alert_latencies),
            "p95ControlAppliedToAlertLatencyMs":
                percentile95(control_alert_latencies),
        },
        "transient": {
            "runs": len(transient),
            "ignored": sum(run["alerts"] == 0 for run in transient),
        },
        "allSessionsCommitted": all(
            run["sessionVerification"] == "VALID_COMMITTED"
            for run in runs
        ),
        "allVideosWithoutLargeGaps": all(
            run["videoLargeGaps"] == 0
            for run in runs
        ),
        "runs": runs,
        "limitations": [
            (
                "The fault is induced through real camera exposure control; "
                "it is not a claim about physical lens obstruction."
            ),
            (
                "Manual scene and hand trials remain necessary for the "
                "flagship demonstration."
            ),
        ],
    }
    output = args.campaign / "analysis.json"
    output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
