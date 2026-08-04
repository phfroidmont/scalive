import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

const connectionRoot = document.documentElement
const themeStorageKey = "scalive.docs.theme"
const exampleControlSelector =
  "[data-example-controls], [data-example-controls] button, [data-example-controls] input, [data-example-controls] select, [data-example-controls] textarea"

function readTheme() {
  try {
    const theme = window.localStorage.getItem(themeStorageKey)
    return theme === "light" || theme === "dark" ? theme : "system"
  } catch (_error) {
    return "system"
  }
}

function applyTheme(theme) {
  if (theme === "light" || theme === "dark") connectionRoot.dataset.theme = theme
  else connectionRoot.removeAttribute("data-theme")
}

function updateExampleControls(connected) {
  if (connected) {
    document.querySelectorAll("[data-disabled-by-connection]").forEach((control) => {
      control.disabled = false
      control.removeAttribute("data-disabled-by-connection")
    })
    return
  }

  document.querySelectorAll(exampleControlSelector).forEach((control) => {
    if (!control.disabled) {
      control.disabled = true
      control.setAttribute("data-disabled-by-connection", "")
    }
  })
}

function updateConnectionState(state, element) {
  connectionRoot.dataset.connectionState = state
  const indicator = element ?? document.querySelector("#docs-connection-status")
  if (indicator) indicator.dataset.connectionState = state
  updateExampleControls(state === "connected")
}

applyTheme(readTheme())
updateConnectionState(navigator.onLine ? "connecting" : "offline")
updateExampleControls(false)

window.addEventListener("online", () => {
  const state = window.liveSocket?.isConnected() ? "connected" : "reconnecting"
  updateConnectionState(state)
})
window.addEventListener("offline", () => updateConnectionState("offline"))

const Hooks = {
  ConnectionStatus: {
    mounted() {
      this.setConnectionState = (state) => {
        this.connectionState = state
        updateConnectionState(state, this.el)
      }

      this.setConnectionState("connected")
    },

    updated() {
      this.setConnectionState(this.connectionState)
    },

    reconnected() {
      this.setConnectionState("connected")
    },

    disconnected() {
      this.setConnectionState(navigator.onLine ? "reconnecting" : "offline")
    },
  },

  ThemeSelector: {
    mounted() {
      this.colorScheme = window.matchMedia("(prefers-color-scheme: dark)")
      this.applyTheme = (theme) => {
        this.theme = theme === "light" || theme === "dark" ? theme : "system"
        applyTheme(this.theme)
        if ("value" in this.el) this.el.value = this.theme
      }
      this.storeTheme = (theme) => {
        try {
          if (theme === "light" || theme === "dark") {
            window.localStorage.setItem(themeStorageKey, theme)
          } else {
            window.localStorage.removeItem(themeStorageKey)
          }
        } catch (_error) {
          // Theme selection still applies when storage is unavailable.
        }
      }
      this.handleThemeChange = (event) => {
        const theme = event.target.value
        this.storeTheme(theme)
        this.applyTheme(theme)
      }
      this.handleColorSchemeChange = () => {
        if (this.theme === "system") this.applyTheme("system")
      }

      this.applyTheme(readTheme())
      this.el.addEventListener("change", this.handleThemeChange)
      this.colorScheme.addEventListener("change", this.handleColorSchemeChange)
    },

    updated() {
      this.applyTheme(this.theme)
    },

    destroyed() {
      this.el.removeEventListener("change", this.handleThemeChange)
      this.colorScheme.removeEventListener("change", this.handleColorSchemeChange)
    },
  },

  PageMetadata: {
    mounted() {
      this.updatePageMetadata = () => {
        const description = this.el.dataset.pageDescription
        const canonical = this.el.dataset.pageCanonical
        const descriptionElement = document.querySelector('meta[name="description"]')
        const canonicalElement = document.querySelector('link[rel="canonical"]')

        if (description !== undefined && descriptionElement) {
          descriptionElement.setAttribute("content", description)
        }
        if (canonical !== undefined && canonicalElement) {
          canonicalElement.setAttribute("href", canonical)
        }
      }

      this.updatePageMetadata()
    },

    updated() {
      this.updatePageMetadata()
    },
  },
}

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const liveSocketParams = csrfToken ? { _csrf_token: csrfToken } : {}

const liveSocket = new LiveSocket("/live", Socket, { params: liveSocketParams, hooks: Hooks })
liveSocket.socket.onError(() => {
  if (connectionRoot.dataset.connectionState !== "connected") {
    updateConnectionState(navigator.onLine ? "reconnecting" : "offline")
  }
})
liveSocket.connect()

window.liveSocket = liveSocket
