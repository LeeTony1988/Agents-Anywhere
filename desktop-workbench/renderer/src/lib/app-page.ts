/**
 * The dashboard pages a hash route may select.
 *
 * Mirrors `web-next/src/lib/app-page.ts`. Keeping the allow-list in one exported
 * place stops it from drifting from the type again: a route missing from the
 * list silently falls back to `home`, which is how `#/device/:id/remote` used to
 * render the composer instead of the remote desktop page.
 */
export type AppPage =
  | "home"
  | "session"
  | "settings"
  | "admin"
  | "mobile-connections"
  | "tokens-anywhere"
  | "device"
  | "device-remote"

export const APP_PAGES: readonly AppPage[] = [
  "home",
  "session",
  "settings",
  "admin",
  "mobile-connections",
  "tokens-anywhere",
  "device",
  "device-remote",
]

/** Every known page resolves to itself; anything else lands on `home`. */
export function resolveAppPage(page: string): AppPage {
  return (APP_PAGES as readonly string[]).includes(page) ? (page as AppPage) : "home"
}
