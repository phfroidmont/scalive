import { LiveSocket } from "phoenix_live_view"

import { nextActiveIndex, search } from "./search.js"
import { createBrowserInteropHook } from "./browser-interop.js"
import { createInlineApiReferenceEnhancer } from "./inline-api-reference.js"
import {
  assertLiveViewVersion,
  createTraceSession,
  createLiveTraceAdapter,
  LiveTraceSocket,
} from "./xray/phoenix-live-view-1.1.28.js"

const connectionRoot = document.documentElement
const themeStorageKey = "scalive.docs.theme"
const exampleControlSelector =
  "[data-example-controls], [data-example-controls] button, [data-example-controls] input, [data-example-controls] select, [data-example-controls] textarea"
const instantSearchLimit = 8
const liveTraceAdapter = createLiveTraceAdapter()
const liveTraceSession = createTraceSession()
const inlineApiReferences = createInlineApiReferenceEnhancer()
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

function enhanceCodeBlocks(root = document) {
  root.querySelectorAll(".docs-code-block").forEach((block) => {
    const copy = block.querySelector("[data-code-copy]")
    if (copy) copy.hidden = false

    const expand = block.querySelector("[data-code-expand]")
    if (expand && !block.classList.contains("docs-code-enhanced")) {
      block.classList.add("docs-code-enhanced", "docs-code-collapsed")
      expand.hidden = false
      expand.setAttribute("aria-expanded", "false")
      expand.textContent = "Show all"
    }
  })
}

function closeNavigationDisclosure() {
  const disclosure = document.querySelector("#docs-navigation-disclosure")
  if (!disclosure) return
  if (window.matchMedia("(max-width: 48rem)").matches) disclosure.removeAttribute("open")
  else disclosure.setAttribute("open", "")
}

document.addEventListener("click", async (event) => {
  const traceCopy = event.target.closest("[data-trace-code-copy]")
  if (traceCopy) {
    const block = traceCopy.closest(".docs-trace-evidence-code")
    const code = block?.querySelector("pre > code")
    const status = block?.querySelector("[data-trace-code-status]")
    if (!code || !status) return

    try {
      await navigator.clipboard.writeText(code.textContent ?? "")
      status.textContent = "JSON copied"
      traceCopy.textContent = "Copied"
      window.setTimeout(() => {
        traceCopy.textContent = "Copy JSON"
      }, 1600)
    } catch (_error) {
      status.textContent = "Unable to copy JSON"
    }
    return
  }

  const traceWrap = event.target.closest("[data-trace-code-wrap]")
  if (traceWrap) {
    const block = traceWrap.closest(".docs-trace-evidence-code")
    if (!block) return
    const wrapped = block.classList.toggle("is-wrapped")
    traceWrap.setAttribute("aria-pressed", String(wrapped))
    traceWrap.textContent = wrapped ? "Do not wrap" : "Wrap lines"
    return
  }

  const traceExpand = event.target.closest("[data-trace-code-expand]")
  if (traceExpand) {
    const block = traceExpand.closest(".docs-trace-evidence-code")
    if (!block) return
    const expanded = block.classList.toggle("is-expanded")
    traceExpand.setAttribute("aria-expanded", String(expanded))
    traceExpand.textContent = expanded ? "Collapse" : "Show all"
    return
  }

  const copy = event.target.closest("[data-code-copy]")
  if (copy) {
    const block = copy.closest(".docs-code-block")
    const code = block?.querySelector(".docs-code > code")
    const status = block?.querySelector("[data-code-status]")
    if (!code || !status) return

    try {
      await navigator.clipboard.writeText(code.textContent ?? "")
      status.textContent = "Code copied"
      copy.textContent = "Copied"
      window.setTimeout(() => {
        copy.textContent = "Copy"
      }, 1600)
    } catch (_error) {
      status.textContent = "Unable to copy code"
    }
    return
  }

  const expand = event.target.closest("[data-code-expand]")
  if (expand) {
    const block = expand.closest(".docs-code-block")
    if (!block) return
    const expanded = block.classList.toggle("docs-code-collapsed") === false
    expand.setAttribute("aria-expanded", String(expanded))
    expand.textContent = expanded ? "Collapse" : "Show all"
  }
})

applyTheme(readTheme())
updateConnectionState(navigator.onLine ? "connecting" : "offline")
updateExampleControls(false)
enhanceCodeBlocks()

window.addEventListener("phx:page-loading-stop", () => {
  enhanceCodeBlocks()
  inlineApiReferences.enhance()
  closeNavigationDisclosure()
})

window.addEventListener("online", () => {
  const state = window.liveSocket?.isConnected() ? "connected" : "reconnecting"
  updateConnectionState(state)
})
window.addEventListener("offline", () => updateConnectionState("offline"))

const Hooks = {
  BrowserInterop: createBrowserInteropHook(),
  LiveTraceViewer: liveTraceAdapter.hook,
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

      this.handleDocumentKeyDown = (event) => {
        if (!(event.ctrlKey || event.metaKey) || event.altKey || event.key.toLowerCase() !== "k") return
        event.preventDefault()
        const disclosure = this.el.closest("details")
        if (disclosure) disclosure.open = true
        this.input.focus()
      }

      this.input.addEventListener("input", this.updateResults)
      this.input.addEventListener("focus", this.updateResults)
      this.input.addEventListener("keydown", this.handleKeyDown)
      document.addEventListener("pointerdown", this.handleDocumentPointerDown)
      document.addEventListener("keydown", this.handleDocumentKeyDown)
    },

    destroyed() {
      this.requestSequence += 1
      this.input.removeEventListener("input", this.updateResults)
      this.input.removeEventListener("focus", this.updateResults)
      this.input.removeEventListener("keydown", this.handleKeyDown)
      document.removeEventListener("pointerdown", this.handleDocumentPointerDown)
      document.removeEventListener("keydown", this.handleDocumentKeyDown)
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
        if ("value" in this.el) {
          const label = `Color theme: ${this.theme[0].toUpperCase()}${this.theme.slice(1)}`
          this.el.value = this.theme
          this.el.setAttribute("aria-label", label)
          this.el.title = label
        }
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

  NavigationDisclosure: {
    mounted() {
      this.mobile = window.matchMedia("(max-width: 48rem)")
      this.syncDisclosure = () => {
        if (this.mobile.matches) this.el.removeAttribute("open")
        else this.el.setAttribute("open", "")
      }
      this.syncDisclosure()
      this.mobile.addEventListener("change", this.syncDisclosure)
    },

    updated() {
      this.syncDisclosure()
    },

    destroyed() {
      this.mobile.removeEventListener("change", this.syncDisclosure)
    },
  },

  ApiNavigation: {
    mounted() {
      this.filter = this.el.querySelector("[data-api-nav-filter]")
      this.revealCurrent = () => {
        const current = this.el.querySelector('[aria-current="page"]')
        if (!current) return
        current.closest("li")?.querySelectorAll(":scope > details").forEach((branch) => {
          branch.setAttribute("open", "")
        })
        let branch = current.parentElement
        while (branch && branch !== this.el) {
          if (branch.tagName === "DETAILS") branch.setAttribute("open", "")
          branch = branch.parentElement
        }
        if (window.matchMedia("(min-width: 48rem)").matches) {
          const container = this.el.closest(".docs-section-nav")
          if (container) container.scrollTop = Math.max(0, current.offsetTop - container.clientHeight / 2)
        }
      }
      this.filterItems = () => {
        const query = this.filter?.value.trim().toLowerCase() ?? ""
        const visit = (item) => {
          const children = [...item.querySelectorAll(":scope > details > ul > li")]
          const descendantMatches = children.map(visit).some(Boolean)
          const ownText = item.dataset.apiNavItem ?? ""
          const matches = query === "" || ownText.includes(query) || descendantMatches
          item.hidden = !matches
          if (query && descendantMatches) item.querySelector(":scope > details")?.setAttribute("open", "")
          return matches
        }
        this.el.querySelectorAll("nav > ul > li").forEach(visit)
        if (!query) this.revealCurrent()
      }

      this.revealCurrent()
      this.filter?.addEventListener("input", this.filterItems)
    },

    updated() {
      this.revealCurrent()
    },

    destroyed() {
      this.filter?.removeEventListener("input", this.filterItems)
    },
  },

  ApiMembers: {
    mounted() {
      this.collectMembers = () => {
        this.filter = this.el.querySelector("[data-api-member-filter]")
        this.status = this.el.querySelector("[data-api-member-status]")
        this.groups = [...this.el.querySelectorAll("[data-api-member-group]")]
        this.members = [...this.el.querySelectorAll("[data-api-member]")]
      }
      this.filterMembers = () => {
        const query = this.filter?.value.trim().toLowerCase() ?? ""
        this.members.forEach((member) => {
          member.hidden = query !== "" && !member.dataset.apiMember.includes(query)
        })
        this.groups.forEach((group) => {
          group.hidden = !group.querySelector("[data-api-member]:not([hidden])")
          const outlineItem = document.querySelector(`.docs-outline a[href$="#${group.id}"]`)?.closest("li")
          if (outlineItem) outlineItem.hidden = group.hidden
        })
        const visible = this.members.filter((member) => !member.hidden).length
        if (this.status) this.status.textContent = `${visible} ${visible === 1 ? "member" : "members"}`
        window.dispatchEvent(new Event("scroll"))
      }

      this.collectMembers()
      this.el.querySelector("[data-api-member-tools]")?.removeAttribute("hidden")
      this.filterMembers()
      this.filter?.addEventListener("input", this.filterMembers)
    },

    updated() {
      this.filter?.removeEventListener("input", this.filterMembers)
      this.collectMembers()
      this.el.querySelector("[data-api-member-tools]")?.removeAttribute("hidden")
      this.filterMembers()
      this.filter?.addEventListener("input", this.filterMembers)
    },

    destroyed() {
      this.filter?.removeEventListener("input", this.filterMembers)
    },
  },

  PageOutline: {
    mounted() {
      this.compact = window.matchMedia("(max-width: 48rem)")
      this.syncDisclosure = () => {
        if (this.compact.matches) this.el.removeAttribute("open")
        else this.el.setAttribute("open", "")
      }
      this.collectSections = () => {
        this.sections = [...this.el.querySelectorAll('a[href*="#"]')].flatMap((link) => {
          const id = new URL(link.href).hash.slice(1)
          const heading = id ? document.getElementById(decodeURIComponent(id)) : null
          return heading ? [{ heading, link }] : []
        })
      }
      this.updateCurrentSection = () => {
        this.frame = undefined
        const sections = this.sections.filter(({ heading, link }) =>
          !heading.closest("[hidden]") && !link.closest("[hidden]")
        )
        if (sections.length === 0) {
          this.sections.forEach(({ link }) => link.removeAttribute("aria-current"))
          return
        }

        const headerBottom = document.querySelector(".docs-header")?.getBoundingClientRect().bottom ?? 0
        const activationLine = headerBottom + 24
        const atDocumentEnd = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 1
        const current = atDocumentEnd
          ? sections[sections.length - 1]
          : sections.reduce(
              (active, section) => section.heading.getBoundingClientRect().top <= activationLine ? section : active,
              sections[0]
            )

        this.sections.forEach(({ link }) => {
          if (link === current.link) link.setAttribute("aria-current", "location")
          else link.removeAttribute("aria-current")
        })
      }
      this.scheduleCurrentSectionUpdate = () => {
        if (this.frame === undefined) this.frame = window.requestAnimationFrame(this.updateCurrentSection)
      }
      this.syncDisclosure()
      this.collectSections()
      this.updateCurrentSection()
      this.compact.addEventListener("change", this.syncDisclosure)
      window.addEventListener("scroll", this.scheduleCurrentSectionUpdate, { passive: true })
      window.addEventListener("resize", this.scheduleCurrentSectionUpdate)
    },

    updated() {
      this.syncDisclosure()
      this.collectSections()
      this.scheduleCurrentSectionUpdate()
    },

    destroyed() {
      this.compact.removeEventListener("change", this.syncDisclosure)
      window.removeEventListener("scroll", this.scheduleCurrentSectionUpdate)
      window.removeEventListener("resize", this.scheduleCurrentSectionUpdate)
      if (this.frame !== undefined) window.cancelAnimationFrame(this.frame)
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

const liveSocket = new LiveSocket("/live", LiveTraceSocket, {
  params: liveSocketParams,
  hooks: Hooks,
  dom: liveTraceAdapter.dom,
  liveTraceAdapter,
  liveTraceSession,
})
assertLiveViewVersion(liveSocket)
liveSocket.socket.onError(() => {
  if (connectionRoot.dataset.connectionState !== "connected") {
    updateConnectionState(navigator.onLine ? "reconnecting" : "offline")
  }
})
liveSocket.connect()

window.liveSocket = liveSocket
