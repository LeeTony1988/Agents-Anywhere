# Build aa-screen-host with the LiveKit publisher on a Windows developer box.
#
# Usage:
#   .\scripts\build-livekit.ps1
#   .\scripts\build-livekit.ps1 -WebrtcDir C:\cache\win-x64-release
#
# If `LK_CUSTOM_WEBRTC` is not provided, webrtc-sys tries to download the
# prebuilt libwebrtc archive from GitHub. This repository's network may block
# that download; extract `webrtc-win-x64-release.zip` and pass its
# `win-x64-release` directory explicitly.
param(
    [string]$WebrtcDir = $env:LK_CUSTOM_WEBRTC,
    [switch]$Release
)

$ErrorActionPreference = "Stop"
if ($WebrtcDir) {
    $resolved = (Resolve-Path $WebrtcDir).Path
    $env:LK_CUSTOM_WEBRTC = $resolved
    Write-Host "LK_CUSTOM_WEBRTC=$resolved"
}

# cl.exe in this image fails with D8050 while writing the cc-rs debug record;
# clang-cl uses the same MSVC ABI and works with the static CRT in .cargo/config.toml.
$env:CC = "clang-cl"
$env:CXX = "clang-cl"

# `wgc` is what actually captures the desktop on Windows: without it the host
# publishes blank stub frames, so a packaged build must always enable it here.
$features = if ($env:AA_SCREEN_HOST_NO_WGC -eq "1") { "livekit" } else { "livekit,wgc" }
$cargoArgs = @("build", "--features", $features)
if ($Release) { $cargoArgs += "--release" }
& cargo @cargoArgs
exit $LASTEXITCODE
