# Drive one host process with a fixed NDJSON script and print its frames.
#
# Useful for manual checks on a real desktop:
#   .\scripts\probe-input.ps1                      # default: debug binary
#   .\scripts\probe-input.ps1 -Binary target\debug\aa-screen-host.exe -Synthetic
#
# It exercises host.start → controlGrant → host.input (pointer/key) →
# releaseAll → shutdown and prints every response, so you can confirm that
# input is accepted and released (no stuck keys) on the machine under test.
param(
    [string]$Binary = "target\debug\aa-screen-host.exe",
    [switch]$Synthetic
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Binary)) { throw "host binary not found: $Binary" }
if ($Synthetic) { $env:AA_SCREEN_HOST_SYNTHETIC_CAPTURE = "1" }
$env:RUST_LOG = $env:RUST_LOG ?? "info"

$frames = @(
    '{"v":1,"type":"request","id":1,"method":"host.start","params":{"sessionId":"rds_probe","displayId":"0","mode":"control","quality":{"maxFps":10,"maxBitrateKbps":800,"maxWidth":320}}}',
    '{"v":1,"type":"request","id":2,"method":"host.controlGrant","params":{"identity":"viewer:pt_probe"}}',
    '{"v":1,"type":"request","id":3,"method":"host.input","params":{"identity":"viewer:pt_probe","events":[{"v":1,"seq":1,"t":"pointer","a":"down","x":0.5,"y":0.4,"b":"left"},{"v":1,"seq":2,"t":"pointer","a":"up","x":0.5,"y":0.4,"b":"left"},{"v":1,"seq":3,"t":"key","a":"down","code":"KeyA"},{"v":1,"seq":4,"t":"text","s":"hi"}]}}',
    '{"v":1,"type":"request","id":4,"method":"host.screenshot","params":{"maxWidth":320,"format":"jpeg"}}',
    '{"v":1,"type":"request","id":5,"method":"host.releaseAll","params":{}}',
    '{"v":1,"type":"shutdown"}'
) -join "`n"

# The host exits on stdin EOF, which also exercises the graceful shutdown path.
$lines = $frames | & $Binary --stdio 2>&1
$lines | Select-Object -First 20
