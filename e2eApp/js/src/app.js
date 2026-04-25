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
  },
  FormHook: {
    mounted() {
      this.el.textContent = "pong"
    }
  },
  FormStreamHook: {
    mounted() {
      const appendPong = () => {
        if (!this.el.textContent.endsWith("pong")) this.el.textContent = `${this.el.textContent}pong`
      }
      if (["items-1", "items-2", "items-3"].includes(this.el.id)) appendPong()
      else window.setTimeout(appendPong, 800)
    }
  },
  InsidePortal: {
    mounted() {
      this.el.setAttribute("data-portalhook-mounted", "true")
    }
  },
  PortalTooltip: {
    mounted() {
      this.tooltipEl = document.getElementById(this.el.dataset.id)
      this.activatorEl = document.getElementById(`${this.el.dataset.id}-activator`)
      this.activatorEl.addEventListener("mouseover", () => this.show())
      this.activatorEl.addEventListener("focusin", () => this.show())
      this.activatorEl.addEventListener("mouseout", () => this.hide())
      this.activatorEl.addEventListener("focusout", () => this.hide())
    },
    show() {
      if (this.el.dataset.show) this.liveSocket.execJS(this.el, this.el.dataset.show)
    },
    hide() {
      if (this.el.dataset.hide) this.liveSocket.execJS(this.el, this.el.dataset.hide)
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

window.addEventListener("reset", () => {
  document.querySelectorAll("[phx-feedback-for]").forEach((el) => {
    el.classList.add("phx-no-feedback")
  })
})
