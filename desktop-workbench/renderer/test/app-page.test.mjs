import assert from "node:assert/strict";
import test from "node:test";

import { APP_PAGES, resolveAppPage } from "../src/lib/app-page.ts";

// Same regression as the Web client: `#/device/:id/remote` fell back to "home"
// because the renderer's allow-list omitted "device-remote".
test("every known page resolves to itself", () => {
  for (const page of APP_PAGES) {
    assert.equal(resolveAppPage(page), page);
  }
});

test("device-remote is a first-class page", () => {
  assert.ok(APP_PAGES.includes("device-remote"));
  assert.equal(resolveAppPage("device-remote"), "device-remote");
  assert.equal(resolveAppPage("device"), "device");
});

test("unknown routes fall back to home", () => {
  for (const value of ["", "/", "unknown", "device-remote/extra"]) {
    assert.equal(resolveAppPage(value), "home", value);
  }
});
