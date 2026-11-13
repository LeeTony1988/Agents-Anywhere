import { contextBridge, ipcRenderer } from "electron";

/**
 * Preload for the always-on-top sharing overlay.
 *
 * The overlay is a tiny local page, not the workbench renderer, so it gets its
 * own minimal bridge instead of the full `desktopWorkbench` API.
 */
contextBridge.exposeInMainWorld("sharingOverlay", {
  showWorkbench: (): Promise<void> => ipcRenderer.invoke("workbench:sharing:show"),
  platform: process.platform,
});
