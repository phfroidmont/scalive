import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"
import topbar from "topbar"

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const liveSocketParams = csrfToken ? { _csrf_token: csrfToken } : {}

const BrowserInterop = {
  mounted() {
    this.isDestroyed = false

    this.handleEvent("browser-copy-request", async (payload) => {
      if (this.isDestroyed) return

      const requestId = typeof payload?.requestId === "string" ? payload.requestId : ""
      const text = typeof payload?.text === "string" ? payload.text : null
      let ok = false

      if (requestId.length > 0 && requestId.length <= 64 && text !== null && text.length <= 4096) {
        try {
          if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(text)
            ok = true
          }
        } catch {
          ok = false
        }
      }

      if (this.isDestroyed) return

      try {
        await this.pushEvent("browser-copy-result", { requestId, ok })
      } catch {
        // The LiveSocket may disconnect while the browser operation is completing.
      }
    })
  },

  destroyed() {
    this.isDestroyed = true
  },
}

const hooks = { BrowserInterop }

let liveSocket = new LiveSocket("/live", Socket, {
  params: liveSocketParams,
  hooks,
})

topbar.config({ barColors: { 0: "#29d" }, shadowColor: "rgba(0, 0, 0, .3)" })
window.addEventListener("phx:page-loading-start", () => topbar.show(300))
window.addEventListener("phx:page-loading-stop", () => topbar.hide())

liveSocket.connect()

// Expose liveSocket on window for web console debug logs and latency simulation:
// >> liveSocket.enableDebug()
// >> liveSocket.enableLatencySim(1000)
// >> liveSocket.disableLatencySim()
window.liveSocket = liveSocket
