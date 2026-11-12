"use client"

import * as React from "react"
import { Monitor } from "lucide-react"
import { useTranslations } from "next-intl"
import { Button } from "@/components/ui/button"
import { useAuth } from "@/components/auth/auth-context"
import { useWorkspace } from "@/components/workspace-context"
import { remoteDesktopApi } from "@/lib/remote-desktop/api"

/**
 * Device-page entry point「看这台设备屏幕」. Renders only when the server-side
 * `connector.remote_desktop.view` capability is fully usable
 * (`supported && available && allowed`), per the capability-driven UI rule.
 * It never touches LiveKit, so a missing/broken media stack cannot affect the
 * device page.
 */
export function RemoteDesktopEntryButton({ connectorId }: { connectorId: string }) {
  const t = useTranslations("dashboard.device")
  const { navigate } = useWorkspace()
  const { session: authSession } = useAuth()
  const [ready, setReady] = React.useState(false)

  React.useEffect(() => {
    const token = authSession?.accessToken
    if (!token) {
      setReady(false)
      return
    }
    let cancelled = false
    remoteDesktopApi
      .getHostInfo(token, connectorId)
      .then((info) => {
        if (cancelled) return
        const view = info.capabilities.find((item) => item.capabilityId === "connector.remote_desktop.view")
        setReady(Boolean(view && view.supported && view.available && view.allowed))
      })
      .catch(() => {
        // Endpoint missing (older server) or capability error: keep the entry hidden.
        if (!cancelled) setReady(false)
      })
    return () => {
      cancelled = true
    }
  }, [authSession?.accessToken, connectorId])

  if (!ready) return null
  return (
    <Button
      variant="outline"
      size="sm"
      className="max-sm:size-8 max-sm:px-0"
      onClick={() => navigate("device-remote", connectorId)}
      aria-label={t("remoteDesktopEntry")}
    >
      <Monitor />
      <span className="max-sm:sr-only">{t("remoteDesktopEntry")}</span>
    </Button>
  )
}
