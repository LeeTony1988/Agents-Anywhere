"use client"

import { MonitorUp } from "lucide-react"
import { useTranslations } from "next-intl"
import { Button } from "@/components/ui/button"
import { useDesktopConnector } from "@/features/desktop/desktop-connector-context"
import { useWorkspace } from "@/components/workspace-context"

/**
 * Local "this device is being shared" indicator for the Desktop shell.
 *
 * The always-on-top native overlay lives in the Electron main process; this
 * in-window banner is the fallback that is always visible while the workbench
 * is open and lets the user jump to the session that is sharing the screen.
 */
export function DesktopSharingBanner() {
  const t = useTranslations("remoteDesktop")
  const { state } = useDesktopConnector()
  const { navigate } = useWorkspace()
  const sharing = state?.remoteDesktop
  if (!sharing?.active) return null
  const isControl = sharing.mode === "control"
  return (
    <div className="flex items-center gap-3 border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-sm text-amber-900 dark:text-amber-100">
      <MonitorUp className="size-4 shrink-0" />
      <div className="min-w-0">
        <span className="font-medium">
          {t("sharingBanner")}
        </span>
        <span className="ml-2 text-muted-foreground">
          {isControl ? t("sharingBannerControl") : t("sharingBannerView")}
        </span>
      </div>
      {state?.connectorId ? (
        <Button
          variant="outline"
          size="sm"
          className="ml-auto"
          onClick={() => navigate("device", state.connectorId)}
        >
          {t("openRemote")}
        </Button>
      ) : null}
    </div>
  )
}
