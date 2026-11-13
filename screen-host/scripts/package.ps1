# Build aa-screen-host and write a verifiable distribution layout.
#
# Output:
#   dist/<platform>-<arch>/aa-screen-host[.exe]
#   dist/<platform>-<arch>/manifest.json   { platform, arch, version, sha256, features }
#
# The Connector refuses a host whose sha256 differs from the manifest, so a
# tampered or half-copied binary cannot be launched.
#
# Usage:
#   .\scripts\package.ps1                       # release build, stub publisher
#   .\scripts\package.ps1 -Features "wgc,livekit"
#   .\scripts\package.ps1 -NoBuild               # re-package an existing binary
param(
    [string]$Features = "",
    [switch]$NoBuild,
    [switch]$DebugBuild
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $root
try {
    $profile = if ($DebugBuild) { "debug" } else { "release" }
    if (-not $NoBuild) {
        $cargoArgs = @("build")
        if (-not $DebugBuild) { $cargoArgs += "--release" }
        if ($Features) { $cargoArgs += @("--features", $Features) }
        & cargo @cargoArgs
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed with exit code $LASTEXITCODE" }
    }

    $platform = if ($IsWindows -or $env:OS -eq "Windows_NT") { "windows" }
        elseif ($IsMacOS) { "macos" } else { "linux" }
    $arch = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture) {
        "X64" { "x86_64" }
        "Arm64" { "aarch64" }
        default { "unknown" }
    }
    $exeName = if ($platform -eq "windows") { "aa-screen-host.exe" } else { "aa-screen-host" }
    $source = Join-Path $root "target/$profile/$exeName"
    if (-not (Test-Path $source)) { throw "host binary not found: $source" }

    $targetDir = Join-Path $root "dist/$platform-$arch"
    New-Item -ItemType Directory -Force $targetDir | Out-Null
    $target = Join-Path $targetDir $exeName
    Copy-Item $source $target -Force

    $version = (& $source --version | Select-Object -First 1).Trim()
    $hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant()
    $featureList = @()
    if ($Features) { $featureList = $Features.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ } }
    $manifest = [ordered]@{
        platform = $platform
        arch = $arch
        version = $version
        sha256 = $hash
        features = $featureList
        bytes = (Get-Item $target).Length
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $targetDir "manifest.json") -Encoding utf8
    Write-Host "packaged $target ($($manifest.bytes) bytes, sha256 $hash)"
}
finally {
    Pop-Location
}
