// docs:start browser-integration-hook
const maxRequestIdLength = 64
const maxTextLength = 4096

export function readCopyRequest(payload) {
  const requestId = typeof payload?.requestId === "string" ? payload.requestId : ""
  const text = typeof payload?.text === "string" ? payload.text : undefined
  if (
    requestId.length === 0 ||
    requestId.length > maxRequestIdLength ||
    text === undefined ||
    text.length > maxTextLength
  ) return undefined
  return { requestId, text }
}

export function createBrowserInteropHook(clipboard = globalThis.navigator?.clipboard) {
  return {
    mounted() {
      this.isDestroyed = false
      this.handleEvent("browser-copy-request", async (payload) => {
        if (this.isDestroyed) return

        const request = readCopyRequest(payload)
        let ok = false
        if (request && clipboard?.writeText) {
          try {
            await clipboard.writeText(request.text)
            ok = true
          } catch {
            ok = false
          }
        }

        if (this.isDestroyed) return
        try {
          await this.pushEvent("browser-copy-result", {
            requestId: request?.requestId ?? "",
            ok,
          })
        } catch {
          // The LiveSocket may disconnect while browser work is completing.
        }
      })
    },

    destroyed() {
      this.isDestroyed = true
    },
  }
}
// docs:end browser-integration-hook
