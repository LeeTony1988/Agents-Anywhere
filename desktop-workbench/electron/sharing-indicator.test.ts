import assert from "node:assert/strict";
import test from "node:test";

import {
  INACTIVE_SHARING,
  parseSharingState,
  sharingIndicatorTransition,
  type SharingState,
} from "./sharing-indicator.js";

const HIDDEN_SURFACES = { overlayPresent: false, statusItemPresent: false };
const VISIBLE_SURFACES = { overlayPresent: true, statusItemPresent: true };

test("parseSharingState reads the connector payload defensively", () => {
  assert.deepEqual(parseSharingState(null), INACTIVE_SHARING);
  assert.deepEqual(parseSharingState(undefined), INACTIVE_SHARING);
  assert.deepEqual(parseSharingState("shared"), INACTIVE_SHARING);
  assert.deepEqual(parseSharingState({ active: false, mode: "control" }), INACTIVE_SHARING);
  assert.deepEqual(parseSharingState({ active: true, mode: "control" }), {
    active: true,
    mode: "control",
  });
  assert.deepEqual(parseSharingState({ active: true, mode: "view" }), {
    active: true,
    mode: "view",
  });
  // An unknown mode still counts as sharing, but without a specific wording.
  assert.deepEqual(parseSharingState({ active: true, mode: "weird" }), {
    active: true,
    mode: null,
  });
});

test("start of sharing shows the overlay", () => {
  assert.equal(
    sharingIndicatorTransition(INACTIVE_SHARING, { active: true, mode: "view" }, HIDDEN_SURFACES),
    "show",
  );
});

test("mode change refreshes an existing overlay", () => {
  const view: SharingState = { active: true, mode: "view" };
  const control: SharingState = { active: true, mode: "control" };
  assert.equal(sharingIndicatorTransition(view, control, VISIBLE_SURFACES), "refresh");
  assert.equal(sharingIndicatorTransition(control, control, VISIBLE_SURFACES), "unchanged");
});

test("an overlay that vanished is recreated even without a state change", () => {
  const control: SharingState = { active: true, mode: "control" };
  assert.equal(
    sharingIndicatorTransition(control, control, { overlayPresent: false, statusItemPresent: true }),
    "show",
  );
});

test("end of sharing hides whatever is still on screen", () => {
  const control: SharingState = { active: true, mode: "control" };
  assert.equal(sharingIndicatorTransition(control, INACTIVE_SHARING, VISIBLE_SURFACES), "hide");
  // A stray overlay (for example after a crash) is still cleaned up.
  assert.equal(
    sharingIndicatorTransition(INACTIVE_SHARING, INACTIVE_SHARING, {
      overlayPresent: true,
      statusItemPresent: false,
    }),
    "hide",
  );
  assert.equal(
    sharingIndicatorTransition(INACTIVE_SHARING, INACTIVE_SHARING, HIDDEN_SURFACES),
    "unchanged",
  );
});
