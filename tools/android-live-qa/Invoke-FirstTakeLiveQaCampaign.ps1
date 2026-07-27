param(
    [string]$Adb = "adb",
    [string]$Apk = "",
    [string]$OutputRoot = "",
    [int]$Repeats = 6
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $Apk) {
    $Apk = Join-Path $repo "apps\android-probe\app\build\outputs\apk\debug\app-debug.apk"
}
if (-not $OutputRoot) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputRoot = Join-Path $repo "evidence\live-qa-$stamp"
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null

if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "ADB not found: $Adb"
}
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "APK not found: $Apk"
}

& $Adb get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "No ADB device is ready"
}
& $Adb install -r $Apk | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed"
}
& $Adb shell svc power stayon usb | Out-Null
& $Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $Adb shell wm dismiss-keyguard | Out-Null

$specifications = @()
for ($iteration = 1; $iteration -le $Repeats; $iteration++) {
    $specifications += [pscustomobject]@{
        Kind = "persistent-bright"
        Profile = "PERSISTENT_BRIGHT"
        DurationSeconds = 16
        Iteration = $iteration
    }
    $specifications += [pscustomobject]@{
        Kind = "transient-bright"
        Profile = "TRANSIENT_BRIGHT"
        DurationSeconds = 10
        Iteration = $iteration
    }
}

$runNumber = 0
foreach ($specification in $specifications) {
    $runNumber += 1
    $runName = "run-{0:D2}-{1}-{2:D2}" -f (
        $runNumber,
        $specification.Kind,
        $specification.Iteration
    )
    $runDirectory = Join-Path $OutputRoot $runName
    $sourceDirectory = Join-Path $runDirectory "source"
    New-Item -ItemType Directory -Force -Path $sourceDirectory | Out-Null

    Write-Output (
        "[{0}/{1}] {2}" -f $runNumber, $specifications.Count, $runName
    )
    & $Adb logcat -c
    & $Adb shell am force-stop dev.firsttake.probe
    & $Adb shell am start `
        -n dev.firsttake.probe/.MainActivity `
        --ez firsttake.auto_start true `
        --el firsttake.auto_start_delay_seconds 2 `
        --el firsttake.auto_stop_after_seconds $specification.DurationSeconds `
        --es firsttake.auto_exposure_probe_profile $specification.Profile `
        --es firsttake.initial_analysis_profile FULL `
        --es firsttake.feedback_mode SILENT | Out-Null

    Start-Sleep -Seconds ($specification.DurationSeconds + 8)
    $sessionId = (
        & $Adb shell (
            "ls -1t /sdcard/Android/data/dev.firsttake.probe/" +
            "files/Movies/FirstTake | head -n 1"
        )
    ).Trim()
    if (-not $sessionId) {
        throw "No session found after $runName"
    }
    $remote = (
        "/sdcard/Android/data/dev.firsttake.probe/files/Movies/" +
        "FirstTake/$sessionId"
    )
    & $Adb pull $remote $sourceDirectory | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not pull $sessionId after $runName"
    }
    [ordered]@{
        schemaVersion = "firsttake.live-qa-run.v1"
        kind = $specification.Kind
        exposureProbeProfile = $specification.Profile
        iteration = $specification.Iteration
        requestedDurationSeconds = $specification.DurationSeconds
        sessionId = $sessionId
    } | ConvertTo-Json | Set-Content (
        Join-Path $runDirectory "run.json"
    )
}

Write-Output "CAMPAIGN=$OutputRoot"
