import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

let liveSocket = new LiveSocket("/live", Socket, {
  reloadJitterMin: 50,
  reloadJitterMax: 500
})

liveSocket.connect()
window.liveSocket = liveSocket

window.addEventListener("phx:navigate", (event) => {
  console.log("navigate event", JSON.stringify(event.detail))
})
