// docs:start browser-integration-hook
export function createBrowserInteropHook(loadCopyModule = () => import("./browser-copy.js")) {
  return {
    mounted() {
      // docs:start browser-integration-hook-load
      this.isDestroyed = false

      // Registered before the import starts, so a request pushed during mount is not lost: it
      // waits here for the module.
      this.handleEvent("browser-copy-request", async (payload) => {
        const module = await copyModule.catch(() => undefined)
        if (!module || this.isDestroyed) return

        const result = await module.copyRequestedText(payload)
        if (this.isDestroyed) return
        try {
          await this.pushEvent("browser-copy-result", result)
        } catch {
          // The LiveSocket may disconnect while browser work is completing.
        }
      })

      const copyModule = loadCopyModule()
      // docs:end browser-integration-hook-load

      // docs:start browser-integration-hook-load-failed
      copyModule.catch((error) => {
        console.error("BrowserInterop could not load its module", error)
        if (this.isDestroyed) return
        this.js().show(this.el.querySelector("[data-browser-hook-failed]"))
      })
      // docs:end browser-integration-hook-load-failed
    },

    destroyed() {
      this.isDestroyed = true
    },
  }
}
// docs:end browser-integration-hook
