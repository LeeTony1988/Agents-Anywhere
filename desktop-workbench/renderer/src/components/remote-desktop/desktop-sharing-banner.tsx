"use client"

import * as React from "react"
import { MonitorUp } from "lucide-react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { useDesktopConnector } from "@/features/desktop/desktop-connector-context"
import { useWorkspace } from "@/components/workspace-context"
import { useAuth } from "@/components/auth/auth-context"
import { remoteDesktopApi, remoteDesktopErrorMessage } from "@/lib/remote-desktop/api"

/**
 * Local "this device is being shared" indicator for the Desktop shell.
 *
 * The always-on-top native overlay lives in the Electron main process; this
 * in-window banner is the fallback that is always visible while the workbench
 * is open. It can jump to the device page or end the session from this device,
 * which is the quickest local way to stop sharing.
 */
export function DesktopSharingBanner() {
  const t = useTranslations("remoteDesktop")
  const { state } = useDesktopConnector()
  const { navigate } = useWorkspace()
  const { session: authSession } = useAuth()
  const [ending, setEnding] = React.useState(false)
  const sharing = state?.remoteDesktop
  if (!sharing?.active) return null
  const isControl = sharing.mode === "control"
  const sessionId = sharing.sessionId ?? null

  const endSharing = async () => {
    const token = authSession?.accessToken
    if (!token || !sessionId) return
    setEnding(true)
    try {
      await remoteDesktopApi.endSession(token, sessionId)
      toast.success(t("sharingEnded"))
    } catch (error) {
      toast.error(remoteDesktopErrorMessage(error))
    } finally {
      setEnding(false)
    }
  }

  return (
    <div className="flex items-center gap-3 border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-sm text-amber-900 dark:text-amber-100">
      <MonitorUp className="size-4 shrink-0" />
      <div className="min-w-0">
        <span className="font-medium">{t("sharingBanner")}</span>
        <span className="ml-2 text-muted-foreground">
          {isControl ? t("sharingBannerControl") : t("sharingBannerView")}
        </span>
      </div>
      <div className="ml-auto flex items-center gap-2">
        {sessionId && authSession?.accessToken ? (
          <Button variant="outline" size="sm" onClick={() => void endSharing()} disabled={ending}>
            {t("endSharing")}
          </Button>
        ) : null}
        {state?.connectorId ? (
          <Button
            variant="outline"
            size="sm"
            onClick={() => navigate("device", state.connectorId)}
          >
            {t("openRemote")}
          </Button>
        ) : null}
      </div>
    </div>
  )
}
