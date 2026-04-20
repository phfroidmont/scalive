import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

const hooks = {
  PhoneNumber: {
    mounted() {
      this.el.addEventListener("input", () => {
        const match = this.el.value.replace(/\D/g, "").match(/^(\d{3})(\d{3})(\d{4})$/)
        if (match) {
          this.el.value = `${match[1]}-${match[2]}-${match[3]}`
        }
      })
    }
  },
  Runtime: {
    mounted() {
      this.el.style.display = "block"
    },
    updated() {
      this.el.style.display = "block"
    }
  }
}

let liveSocket = new LiveSocket("/live", Socket, {
  reloadJitterMin: 50,
  reloadJitterMax: 500,
  hooks
})

liveSocket.connect()
window.liveSocket = liveSocket

window.addEventListener("phx:js:exec", (event) => {
  const cmd = event?.detail?.cmd
  if (typeof cmd === "string" && liveSocket.main) {
    liveSocket.execJS(liveSocket.main.el, cmd)
  }
})

window.addEventListener("phx:navigate", (event) => {
  console.log("navigate event", JSON.stringify(event.detail))
})
