import { LiveSocket } from "phoenix_live_view"

import { nextActiveIndex, search } from "./search.js"
import {
  assertLiveViewVersion,
  createTraceSession,
  createXRayAdapter,
  XRaySocket,
} from "./xray/phoenix-live-view-1.1.28.js"

const connectionRoot = document.documentElement
const themeStorageKey = "scalive.docs.theme"
const exampleControlSelector =
  "[data-example-controls], [data-example-controls] button, [data-example-controls] input, [data-example-controls] select, [data-example-controls] textarea"
const instantSearchLimit = 8
const xrayAdapter = createXRayAdapter()
const xrayTraceSession = createTraceSession()
const searchKindLabels = {
  page: "Page",
  heading: "Heading",
  example: "Example",
  apiSymbol: "API",
  compatibility: "Compatibility",
}

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
  XRayInspector: xrayAdapter.hook,
  DocumentationSearch: {
    mounted() {
      this.input = this.el.querySelector("input[name='q']")
      this.resultsElement = this.el.querySelector("[role='listbox']")
      this.statusElement = this.el.querySelector("[role='status']")
      this.entriesPromise = undefined
      this.results = []
      this.options = []
      this.activeIndex = -1
      this.requestSequence = 0

      this.closeResults = () => {
        this.requestSequence += 1
        this.results = []
        this.options = []
        this.activeIndex = -1
        this.resultsElement.replaceChildren()
        this.resultsElement.hidden = true
        this.input.setAttribute("aria-expanded", "false")
        this.input.removeAttribute("aria-activedescendant")
        this.statusElement.textContent = ""
      }

      this.setActiveIndex = (index) => {
        this.activeIndex = index
        this.options.forEach((option, optionIndex) => {
          option.setAttribute("aria-selected", String(optionIndex === index))
        })
        const active = this.options[index]
        if (active) {
          this.input.setAttribute("aria-activedescendant", active.id)
          active.scrollIntoView({ block: "nearest" })
        } else {
          this.input.removeAttribute("aria-activedescendant")
        }
      }

      this.loadEntries = () => {
        if (!this.entriesPromise) {
          this.entriesPromise = fetch(this.el.dataset.searchIndex, {
            headers: { Accept: "application/json" },
          }).then((response) => {
            if (!response.ok) throw new Error(`Search index returned ${response.status}`)
            return response.json()
          }).then((entries) => {
            if (!Array.isArray(entries)) throw new Error("Search index is not an array")
            return entries
          })
        }
        return this.entriesPromise
      }

      this.renderResults = () => {
        this.resultsElement.replaceChildren()
        this.options = []
        this.activeIndex = -1

        if (this.results.length === 0) {
          const empty = document.createElement("p")
          empty.className = "docs-global-search-empty"
          empty.textContent = "No instant results. Submit to view the search page."
          this.resultsElement.append(empty)
          this.statusElement.textContent = "No instant search results"
        } else {
          this.results.forEach((entry, index) => {
            const option = document.createElement("a")
            const title = document.createElement("span")
            const kind = document.createElement("span")
            const description = document.createElement("span")
            const fragment = entry.fragment ? `#${entry.fragment}` : ""

            option.id = `docs-global-search-option-${index}`
            option.href = `${entry.route}${fragment}`
            option.className = "docs-global-search-option"
            option.setAttribute("role", "option")
            option.setAttribute("aria-selected", "false")
            title.className = "docs-global-search-option-title"
            title.textContent = entry.title
            kind.className = "docs-global-search-option-kind"
            kind.textContent = searchKindLabels[entry.kind] ?? entry.kind
            description.className = "docs-global-search-option-description"
            description.textContent = entry.description
            option.append(title, kind, description)
            this.resultsElement.append(option)
            this.options.push(option)
          })
          this.statusElement.textContent = `${this.results.length} instant search results`
        }

        this.resultsElement.hidden = false
        this.input.setAttribute("aria-expanded", "true")
      }

      this.updateResults = async () => {
        const query = this.input.value.trim()
        const sequence = ++this.requestSequence
        if (!query) {
          this.closeResults()
          return
        }

        try {
          const entries = await this.loadEntries()
          if (sequence !== this.requestSequence) return
          this.results = search(query, entries, instantSearchLimit)
          this.renderResults()
        } catch (_error) {
          if (sequence !== this.requestSequence) return
          this.closeResults()
          this.statusElement.textContent = "Instant search unavailable; submit the form to search"
        }
      }

      this.handleKeyDown = (event) => {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          if (this.options.length === 0) return
          event.preventDefault()
          this.setActiveIndex(
            nextActiveIndex(this.activeIndex, event.key, this.options.length),
          )
        } else if (event.key === "Enter" && this.activeIndex >= 0) {
          event.preventDefault()
          this.options[this.activeIndex].click()
        } else if (event.key === "Escape") {
          event.preventDefault()
          this.closeResults()
          this.input.focus()
        }
      }

      this.handleDocumentPointerDown = (event) => {
        if (!this.el.contains(event.target)) this.closeResults()
      }

      this.input.addEventListener("input", this.updateResults)
      this.input.addEventListener("focus", this.updateResults)
      this.input.addEventListener("keydown", this.handleKeyDown)
      document.addEventListener("pointerdown", this.handleDocumentPointerDown)
    },

    destroyed() {
      this.requestSequence += 1
      this.input.removeEventListener("input", this.updateResults)
      this.input.removeEventListener("focus", this.updateResults)
      this.input.removeEventListener("keydown", this.handleKeyDown)
      document.removeEventListener("pointerdown", this.handleDocumentPointerDown)
    },
  },

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
        const indexable = this.el.dataset.pageIndexable === "true"
        const descriptionElement = document.querySelector('meta[name="description"]')
        const canonicalElement = document.querySelector('link[rel="canonical"]')
        let robotsElement = document.querySelector("#docs-robots")

        if (description !== undefined && descriptionElement) {
          descriptionElement.setAttribute("content", description)
        }
        if (canonical !== undefined && canonicalElement) {
          canonicalElement.setAttribute("href", canonical)
        }
        if (indexable && robotsElement) {
          robotsElement.remove()
        } else if (!indexable && !robotsElement) {
          robotsElement = document.createElement("meta")
          robotsElement.id = "docs-robots"
          robotsElement.name = "robots"
          robotsElement.content = "noindex,follow"
          document.head.append(robotsElement)
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

const liveSocket = new LiveSocket("/live", XRaySocket, {
  params: liveSocketParams,
  hooks: Hooks,
  dom: xrayAdapter.dom,
  xrayAdapter,
  xrayTraceSession,
})
assertLiveViewVersion(liveSocket)
liveSocket.socket.onError(() => {
  if (connectionRoot.dataset.connectionState !== "connected") {
    updateConnectionState(navigator.onLine ? "reconnecting" : "offline")
  }
})
liveSocket.connect()

window.liveSocket = liveSocket
