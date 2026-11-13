import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { screenshotFilename } from "../src/lib/remote-desktop/screenshot.ts";

// The naming rule is shared by Web, Desktop and Android; the cases live in the
// contract so the three implementations cannot drift apart.
const contractPath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../contracts/remote-desktop/1.0/screenshot-naming.json",
);

test("file names match the shared contract cases", () => {
  const contract = JSON.parse(readFileSync(contractPath, "utf8"));
  assert.ok(Array.isArray(contract.cases) && contract.cases.length > 0);
  for (const item of contract.cases) {
    assert.equal(
      screenshotFilename(item.connectorId, item.capturedAt, item.format),
      item.fileName,
      `${item.connectorId} / ${item.capturedAt} / ${item.format}`,
    );
  }
});
