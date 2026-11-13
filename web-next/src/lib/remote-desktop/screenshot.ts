import type { RemoteDesktopScreenshotResponse } from "@/generated/remote-desktop/v1_0/screenshot-response";

/**
 * Helpers for the "capture a still frame" action.
 *
 * The screenshot response carries `dataBase64` plus (in practice) a `format`
 * field: the host may only be able to encode one of JPEG/PNG, so the client must
 * decode by the returned format instead of assuming the one it asked for.
 */

export type RemoteScreenshot = RemoteDesktopScreenshotResponse & { format?: string | null };

export function screenshotMimeType(format: string | null | undefined): string {
  const normalized = (format ?? "").trim().toLowerCase();
  if (normalized === "jpeg" || normalized === "jpg") return "image/jpeg";
  return "image/png";
}

export function screenshotExtension(format: string | null | undefined): "jpg" | "png" {
  return screenshotMimeType(format) === "image/jpeg" ? "jpg" : "png";
}

/** `remote-<label>-20261113-183000.jpg` — stable, sortable, filesystem-safe. */
export function screenshotFilename(
  label: string,
  capturedAt: string,
  format: string | null | undefined,
): string {
  const safeLabel = (label || "remote")
    .replace(/[^\w.-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48) || "remote";
  const digits = (capturedAt || "").replace(/\D+/g, "");
  const stamp = digits.length >= 14 ? digits.slice(0, 14) : digits || "unknown";
  return `remote-${safeLabel}-${stamp}.${screenshotExtension(format)}`;
}

export function decodeBase64(base64: string): ArrayBuffer {
  const cleaned = (base64 || "").replace(/\s+/g, "");
  if (!cleaned) return new ArrayBuffer(0);
  const binary = atob(cleaned);
  const buffer = new ArrayBuffer(binary.length);
  const bytes = new Uint8Array(buffer);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return buffer;
}

export function screenshotBlob(screenshot: RemoteScreenshot): Blob {
  return new Blob([decodeBase64(screenshot.dataBase64)], {
    type: screenshotMimeType(screenshot.format),
  });
}
