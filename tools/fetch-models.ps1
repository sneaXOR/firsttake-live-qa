[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$modelUrl = (
    "https://storage.googleapis.com/mediapipe-models/" +
    "hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
)
$expectedSha256 = (
    "FBC2A30080C3C557093B5DDFC334698132EB341044CCEE322CCF8BCF3607CDE1"
)
$assetDirectory = Join-Path $PSScriptRoot (
    "..\app\src\main\assets"
)
$destination = Join-Path $assetDirectory "hand_landmarker.task"

New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null

if (Test-Path -LiteralPath $destination -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    if ($existingHash -eq $expectedSha256) {
        Write-Output "Model already verified: $destination"
        exit 0
    }
    throw "Existing model has an unexpected SHA-256: $existingHash"
}

$temporary = Join-Path $env:TEMP (
    "firsttake-hand-landmarker-" + [guid]::NewGuid().ToString() + ".task"
)
try {
    Invoke-WebRequest -Uri $modelUrl -OutFile $temporary
    $actualHash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash
    if ($actualHash -ne $expectedSha256) {
        throw "Downloaded model SHA-256 mismatch: $actualHash"
    }
    [System.IO.File]::Move($temporary, $destination)
    Write-Output "Downloaded and verified: $destination"
} finally {
    if ([System.IO.File]::Exists($temporary)) {
        [System.IO.File]::Delete($temporary)
    }
}
