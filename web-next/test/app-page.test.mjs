import assert from "node:assert/strict";
import test from "node:test";

import { APP_PAGES, resolveAppPage } from "../src/lib/app-page.ts";

// Regression: `#/device/:id/remote` used to fall back to "home" because the
// runtime allow-list in workspace-context did not include "device-remote".
test("every known page resolves to itself", () => {
  for (const page of APP_PAGES) {
    assert.equal(resolveAppPage(page), page);
  }
});

test("device-remote is a first-class page", () => {
  assert.ok(APP_PAGES.includes("device-remote"));
  assert.equal(resolveAppPage("device-remote"), "device-remote");
  // The plain device page keeps working next to it.
  assert.equal(resolveAppPage("device"), "device");
});

test("unknown routes fall back to home", () => {
  for (const value of ["", "/", "unknown", "device-remote/extra", "Device-Remote"]) {
    assert.equal(resolveAppPage(value), "home", value);
  }
});
