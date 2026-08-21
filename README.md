# FirstTake

FirstTake is an offline Android capture guard for egocentric data collection.
It detects correctable failures while the operator can still fix the take,
without putting best-effort analysis on the video or IMU writer path.

The prototype records real 1080p video and IMU data and provides immediate
voice and haptic feedback for:

- a tracked hand approaching or leaving the frame;
- a tracked hand disappearing;
- a persistently covered or dark camera;
- sustained camera shake or a bad capture angle;
- persistent blur, overexposure, or a frame stream that stops responding.

Short transients are ignored. Ambiguous observations remain `UNKNOWN` rather
than becoming confident warnings.

## Why this repository exists

Post-capture QA is still necessary. FirstTake explores a complementary point in
the pipeline: prevent a correctable capture error from becoming an entire take
that must be reviewed or recollected.

This is a real native Android implementation, not a simulated web demo. The
repository is split into:

- `liveqa-android`: the reusable on-device capture and QA module;
- `app`: a thin reference application that proves the module against a real
  CameraX recording lifecycle;
- `tools`: independent evidence and campaign verification.

The module produces:

```text
capture.mp4
session.mcap
session.wal
qa-events.jsonl
manifest.json
postflight.json
hashes.sha256
```

The MCAP contains raw gyroscope and accelerometer samples, clock anchors,
capture events, analysis-frame timestamps, and runtime profile changes.

## Design

- CameraX owns the 1080p recording path.
- Camera analysis uses `KEEP_ONLY_LATEST` and bounded work.
- MediaPipe provides a conservative on-device hand baseline.
- Deterministic checks handle lighting, obstruction, blur, freeze, and motion.
- Runtime profiles degrade `FULL → BALANCED → LOW_POWER → SAFETY_ONLY` before
  analysis can threaten capture.
- Voice and vibration are rate-limited and recovery-aware.
- Session telemetry is append-only and hash-chained.
- Interrupted sessions are inspected and recovered without modifying sources.

The reusable decision logic is separated from the host application in monitor
and policy classes such as `FrameQualityMonitor`, `HandVisibilityMonitor`,
`AnalyzerBudgetController`, and `RuntimePolicy`. The sample app contains only
the activity and presentation needed to exercise the module.

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
pwsh ./tools/fetch-models.ps1
./gradlew.bat testDebugUnitTest
./gradlew.bat assembleDebug :liveqa-android:assembleRelease
```

The hand model is downloaded from the official MediaPipe model bucket and
verified against a pinned SHA-256 before use. It is intentionally not committed.

Python verification tests:

```powershell
python -m pip install pytest
python -m pytest tools/evidence tools/android-live-qa
```
