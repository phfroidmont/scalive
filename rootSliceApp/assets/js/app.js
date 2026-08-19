import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

if (!window.liveSocket) {
  const token = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
  const liveSocket = new LiveSocket("/live", Socket, {
    params: { _csrf_token: token }
  })

  liveSocket.connect()
  window.liveSocket = liveSocket
}
