/**
 * Pure state transitions for the desktop sharing indicator.
 *
 * Kept separate from `main.ts` so the decision logic (when to create, refresh
 * or tear down the overlay and the macOS menu-bar item) is unit-testable
 * without an Electron runtime.
 */

export type SharingState = {
  active: boolean;
  mode: "view" | "control" | null;
};

export type SharingTransition = "unchanged" | "hide" | "show" | "refresh";

export const INACTIVE_SHARING: SharingState = { active: false, mode: null };

/** Read the Connector's local `remoteDesktop` payload defensively. */
export function parseSharingState(value: unknown): SharingState {
  if (!value || typeof value !== "object") return INACTIVE_SHARING;
  const record = value as { active?: unknown; mode?: unknown };
  const active = record.active === true;
  if (!active) return INACTIVE_SHARING;
  const mode = record.mode === "control" || record.mode === "view" ? record.mode : null;
  return { active: true, mode };
}

/** What the caller should do with the overlay/menu item for one update. */
export function sharingIndicatorTransition(
  previous: SharingState,
  next: SharingState,
  surfaces: { overlayPresent: boolean; statusItemPresent: boolean },
): SharingTransition {
  if (!next.active) {
    return previous.active || surfaces.overlayPresent || surfaces.statusItemPresent
      ? "hide"
      : "unchanged";
  }
  if (!surfaces.overlayPresent) return "show";
  if (!previous.active || previous.mode !== next.mode) return "refresh";
  return "unchanged";
}
