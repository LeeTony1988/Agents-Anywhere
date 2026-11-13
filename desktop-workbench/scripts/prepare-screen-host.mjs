// Copy the packaged screen host into the Electron resources layout.
//
// The Connector resolves the host from `resources/screen-host/<platform>-<arch>/`
// (see connector/local/remote_desktop/host.py) and verifies the sha256 in the
// sibling manifest.json before launching it.
//
// Packaging must not fail when the Rust host has not been built: a desktop
// build without `screen-host/dist` simply ships no host, and the remote
// desktop capability reports `host_not_installed` at runtime.
import { cp, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.resolve(root, "..", "screen-host", "dist");
const target = path.join(root, "screen-host-bundle");

await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });

let copied = [];
try {
  await cp(source, target, { recursive: true });
  copied = (await readdir(target)).filter((entry) => !entry.startsWith("."));
} catch (error) {
  if (error?.code !== "ENOENT") throw error;
}

if (copied.length === 0) {
  await writeFile(
    path.join(target, "README.md"),
    [
      "No screen host was bundled with this build.",
      "",
      "Run `screen-host/scripts/build-livekit.ps1` (or a plain `cargo build --release`)",
      "and then `screen-host/scripts/package.ps1` before packaging, so that",
      "screen-host/dist/<platform>-<arch>/ contains the binary and manifest.json.",
      "",
    ].join("\n"),
    "utf8",
  );
  console.log("screen host not bundled: screen-host/dist is missing");
} else {
  console.log(`bundled screen host: ${copied.join(", ")}`);
}
