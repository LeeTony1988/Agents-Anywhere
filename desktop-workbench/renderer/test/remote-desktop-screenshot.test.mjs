import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeBase64,
  screenshotExtension,
  screenshotFilename,
  screenshotMimeType,
} from "../src/lib/remote-desktop/screenshot.ts";

// The screenshot response's `format` is authoritative: the host may only be able
// to encode PNG even when the client asks for JPEG.
test("the returned format decides the mime type", () => {
  assert.equal(screenshotMimeType("jpeg"), "image/jpeg");
  assert.equal(screenshotMimeType("JPEG"), "image/jpeg");
  assert.equal(screenshotMimeType("jpg"), "image/jpeg");
  assert.equal(screenshotMimeType("png"), "image/png");
  // Missing or unknown values fall back to PNG, matching the host's fallback.
  assert.equal(screenshotMimeType(undefined), "image/png");
  assert.equal(screenshotMimeType(null), "image/png");
  assert.equal(screenshotMimeType("webp"), "image/png");
});

test("the extension follows the mime type", () => {
  assert.equal(screenshotExtension("jpeg"), "jpg");
  assert.equal(screenshotExtension("png"), "png");
  assert.equal(screenshotExtension(undefined), "png");
});

test("filenames are sortable and filesystem safe", () => {
  assert.equal(
    screenshotFilename("conn_abc", "2026-11-13T18:30:00Z", "jpeg"),
    "remote-conn_abc-20261113183000.jpg",
  );
  assert.equal(
    screenshotFilename("My Device / dev:1", "2026-11-13T18:30:00.123Z", "png"),
    "remote-My-Device-dev-1-20261113183000.png",
  );
  // Missing pieces still produce something usable.
  assert.equal(screenshotFilename("", "", undefined), "remote-remote-unknown.png");
});

test("base64 decoding yields the raw bytes", () => {
  // JPEG SOI + EOI.
  const jpeg = "ffd8ffd9";
  const base64 = Buffer.from(jpeg, "hex").toString("base64");
  assert.deepEqual(Buffer.from(decodeBase64(base64)), Buffer.from(jpeg, "hex"));
  // Whitespace/newlines are tolerated.
  assert.deepEqual(
    Buffer.from(decodeBase64(`${base64.slice(0, 4)}\n${base64.slice(4)}`)),
    Buffer.from(jpeg, "hex"),
  );
  assert.equal(decodeBase64("").byteLength, 0);
});
