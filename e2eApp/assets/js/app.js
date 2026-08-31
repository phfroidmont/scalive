import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"
import colocated, { hooks as colocatedHooks } from "./colocated/index.js"

const originalConsoleLog = console.log.bind(console)
console.log = (...args) => {
  const first = args[0]
  if (
    window.location.pathname !== "/errors" &&
    typeof first === "string" &&
    /^phx-[\w-]+ (mount|update):/.test(first)
  ) return
  originalConsoleLog(...args)
}

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const liveSocketParams = csrfToken ? { _csrf_token: csrfToken } : {}

if (window.location.pathname === "/issues/4212") window.__lvCustomElLog = []

if (!customElements.get("lv-custom-el")) {
  customElements.define("lv-custom-el", class extends HTMLElement {
    connectedCallback() {
      window.__lvCustomElLog?.push({ type: "connected", id: this.id })
    }

    disconnectedCallback() {
      window.__lvCustomElLog?.push({ type: "disconnected", id: this.id })
    }

    connectedMoveCallback() {
      window.__lvCustomElLog?.push({ type: "moved", id: this.id })
    }
  })
}

if (!customElements.get("issue-4323-face")) {
  customElements.define("issue-4323-face", class extends HTMLElement {
    static formAssociated = true

    constructor() {
      super()
      this.attachInternals()
    }
  })
}

if (!customElements.get("issue-4323-delegates-face")) {
  customElements.define("issue-4323-delegates-face", class extends HTMLElement {
    static formAssociated = true

    constructor() {
      super()
      this.attachInternals()
      this.attachShadow({ mode: "open", delegatesFocus: true })
      this.shadowRoot.innerHTML = '<input type="text"><slot></slot>'
    }
  })
}

if (!window.unsavedFormListenersInstalled) {
  window.unsavedFormListenersInstalled = true
  window.unsavedEvents = window.unsavedEvents || []

  const hasUnsavedChanges = () =>
    document.querySelector("[data-scalive-navigation-guard]") !== null

  window.addEventListener("phx:before-navigate", (event) => {
    if (!hasUnsavedChanges()) return

    window.unsavedEvents.push({ type: "phx", detail: event.detail })
  })

  window.addEventListener("beforeunload", (event) => {
    if (!hasUnsavedChanges()) return

    window.unsavedEvents.push({ type: "beforeunload" })
  })
}

if (window.location.pathname === "/issues/4325") {
  window.issue4325Lifecycle = { mounted: 0, updated: 0, destroyed: 0 }
}

const hooks = {
  ...colocatedHooks,
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
  PagePositionNotifier: {
    mounted() {
      this.pushEvent("page_position_update", {})
    }
  },
  QueuedUploaderHook: {
    mounted() {
      const maxConcurrency = Number.parseInt(this.el.dataset.maxConcurrency || "3", 10)
      let filesRemaining = []
      let queuedSignature = null

      const queueFiles = (event) => {
        event.preventDefault()

        if (!(event.target instanceof HTMLInputElement)) return
        if (!event.target.files) return

        const rawFiles = Array.from(event.target.files)
        const signature = rawFiles
          .map((file) => `${file.name}:${file.size}:${file.lastModified}`)
          .join("|")

        if (signature === queuedSignature) return
        queuedSignature = signature

        const fileNames = rawFiles.map((file) => file.name)

        this.pushEvent("upload_scrub_list", { file_names: fileNames }, ({ deduped_filenames }) => {
          const files = rawFiles.filter((file) => deduped_filenames.includes(file.name))
          const firstFiles = files.slice(0, maxConcurrency)

          filesRemaining = files.slice(maxConcurrency)
          this.upload("files", firstFiles)
        })
      }

      this.el.addEventListener("input", queueFiles)

      this.handleEvent("upload_send_next_file", () => {
        const nextFile = filesRemaining.shift()

        if (nextFile) this.upload("files", [nextFile])
        else console.log("Done uploading, noop!")
      })
    }
  },
  JsUpload: {
    mounted() {
      this.el.addEventListener("click", () => {
        const fillBefore = "before" in this.el.dataset
        if (fillBefore) this.fillInput()
        this.jsUpload()
        if (!fillBefore) this.fillInput()
      })
    },
    jsUpload() {
      const content = "x".repeat(1024).repeat(1024)
      const file = new File([content], "1mb_of_x.txt", { type: "text/plain" })
      const input = document.querySelector("input[type=file]")
      this.uploadTo(input.form, input.name, [file])
    },
    fillInput() {
      const input = document.querySelector('input[type="text"]')
      input.value = input.value + input.value.length
      input.dispatchEvent(new Event("input", { bubbles: true }))
    }
  },
  Issue2835UploadSync: {
    mounted() {
      this.onUploadChange = (event) => {
        if (!(event.target instanceof HTMLInputElement) || event.target.type !== "file") return
        if (document.querySelectorAll("#uploaded-files li").length > 0) return

        this.finishUploadSync()
        this.uploadInput = event.target
        this.maxActiveRefs = 0
        this.uploadProgressObserved = false
        this.uploadSyncMarker = document.createElement("span")
        this.uploadSyncMarker.hidden = true
        this.uploadSyncMarker.className = "phx-change-loading"
        document.body.append(this.uploadSyncMarker)
        this.uploadSyncObserver = new MutationObserver(() => this.checkUploadSync())
        this.uploadSyncObserver.observe(this.el, {
          attributes: true,
          childList: true,
          subtree: true
        })
        this.uploadSyncTimeout = window.setTimeout(() => this.finishUploadSync(), 10000)
        this.checkUploadSync()
      }
      this.el.addEventListener("change", this.onUploadChange)
    },
    updated() {
      this.checkUploadSync()
    },
    destroyed() {
      this.el.removeEventListener("change", this.onUploadChange)
      this.finishUploadSync()
    },
    checkUploadSync() {
      if (!this.uploadSyncMarker || !this.uploadInput) return

      const refs = (name) =>
        (this.uploadInput.getAttribute(name) || "").split(",").filter(Boolean)
      const activeCount = refs("data-phx-active-refs").length
      this.maxActiveRefs = Math.max(this.maxActiveRefs, activeCount)
      if (
        this.maxActiveRefs > 0 &&
        (activeCount < this.maxActiveRefs ||
          refs("data-phx-done-refs").length > 0 ||
          refs("data-phx-preflighted-refs").length > 0)
      ) {
        this.uploadProgressObserved = true
      }
      if (!this.uploadProgressObserved) return

      window.clearTimeout(this.uploadSettleTimeout)
      this.uploadSettleTimeout = window.setTimeout(() => this.finishUploadSync(), 40)
    },
    finishUploadSync() {
      this.uploadSyncObserver?.disconnect()
      this.uploadSyncMarker?.remove()
      window.clearTimeout(this.uploadSyncTimeout)
      window.clearTimeout(this.uploadSettleTimeout)
      this.uploadSyncObserver = null
      this.uploadSyncMarker = null
      this.uploadInput = null
    }
  },
  OuterHook: {
    mounted() {
      this.pushEvent("lol")
    }
  },
  InnerHook: {
    mounted() {
      this.handleEvent("myevent", () => {
        setTimeout(() => {
          this.pushEvent("reload", {})
        }, 50)
      })
    },
    destroyed() {
      const notice = document.getElementById("notice")
      if (notice) notice.innerHTML = ""
    }
  },
  InsidePortal: {
    mounted() {
      this.el.setAttribute("data-portalhook-mounted", "true")
    }
  },
  TeleportedLCButton: {
    mounted() {
      this.el.addEventListener("click", () => {
        this.el.classList.add("phx-click-loading")
        this.pushEventTo(this.el, "prepend").finally(() => {
          this.el.classList.remove("phx-click-loading")
        })
      })
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
  },
  ErrorLogger: {
    mounted() {
      this.logMessages()
    },
    updated() {
      this.logMessages()
    },
    logMessages() {
      const messages = this.el.dataset.consoleMessages
      if (!messages) return
      if (messages === this.loggedMessages) return
      this.loggedMessages = messages
      JSON.parse(messages).forEach((message) => console.log(message))
    }
  },
  MyHook: {
    mounted() {
      console.log("Hook mounted!")
    }
  },
  test: {
    mounted() {
      console.log(`${this.__view().id} mounted hook!`)
      document.querySelector("#issue-3530-sync")?.remove()
    }
  },
  Issue3530Sync: {
    mounted() {
      this.el.querySelector("div[phx-click=inc]").addEventListener("click", () => {
        document.querySelector("#issue-3530-sync")?.remove()
        const marker = document.createElement("span")
        marker.id = "issue-3530-sync"
        marker.hidden = true
        marker.className = "phx-click-loading"
        document.body.append(marker)
        window.setTimeout(() => marker.remove(), 10000)
      })
    }
  },
  Issue4066Hook: {
    mounted() {
      this.el.addEventListener("input", () => {
        window.setTimeout(() => {
          this.pushEventTo(this.el, "do-something", { value: 100 })
          document.body.setAttribute("data-pushed", "yes")
        }, Number.parseInt(this.el.dataset.delay, 10))
      })
    }
  },
  Issue4088Hook: {
    mounted() {
      this.pushEventTo(this.el, "my_update", {})
      this.pushEventTo(this.el, "my_update", {})
      this.pushEventTo(this.el, "my_update", {})
    }
  },
  HookOutside: {
    mounted() {
      console.log("HookOutside mounted")
    }
  },
  ".LockedPanel": {
    mounted() {
      this.el.querySelector("#start-locked-update").addEventListener("click", () => {
        this.pushEventTo("#slow-target-child", "hold-lock", {})
        document.querySelector("#outside-count").textContent = "1"
      })
    }
  },
  IdPassthrough: {
    mounted() {
      window.issue4325Lifecycle.mounted++
      this.js().setAttribute(this.el, "id", this.el.id)
    },
    updated() {
      window.issue4325Lifecycle.updated++
    },
    destroyed() {
      window.issue4325Lifecycle.destroyed++
    }
  },
  RootChange: {
    mounted() {
      console.log("MyHook mounted")
    },
    updated() {
      console.log("MyHook updated")
    }
  }
}

const uploaders = {
  TestExternal(entries) {
    entries.forEach((entry) => {
      document.documentElement.dataset.externalUploadStarted = entry.ref

      const upload = {
        abort() {
          document.documentElement.dataset.externalUploadAborted = entry.ref
        }
      }

      entry.onCancel(() => upload.abort())
    })
  }
}

let liveSocket = new LiveSocket("/live", Socket, {
  reloadJitterMin: 50,
  reloadJitterMax: window.location.pathname === "/errors" ? 50 : 500,
  maxReloads: 5,
  failsafeJitter: 1000,
  rejoinAfterMs: () => 50,
  params: liveSocketParams,
  hooks,
  uploaders,
  cascadePhxRemoveOnNavigation:
    new URLSearchParams(window.location.search).get("cascadePhxRemoveOnNavigation") !== "false"
})

liveSocket.connect()
window.liveSocket = liveSocket
colocated.js_exec(liveSocket)

let formRecoveryMarker = null
let formRecoveryTimeout = null
const finishFormRecovery = () => {
  window.clearTimeout(formRecoveryTimeout)
  formRecoveryMarker?.remove()
  formRecoveryMarker = null
}
const originalDisconnect = liveSocket.disconnect.bind(liveSocket)
liveSocket.disconnect = (...args) => {
  if (
    window.location.pathname.startsWith("/form") &&
    new URLSearchParams(window.location.search).has("disabled-fieldset")
  ) {
    finishFormRecovery()
    formRecoveryMarker = document.createElement("span")
    formRecoveryMarker.hidden = true
    formRecoveryMarker.className = "phx-change-loading"
    document.body.append(formRecoveryMarker)
    formRecoveryTimeout = window.setTimeout(finishFormRecovery, 5000)
  }
  return originalDisconnect(...args)
}
const originalSocketPush = liveSocket.socket.push.bind(liveSocket.socket)
liveSocket.socket.push = (data) => {
  const result = originalSocketPush(data)
  if (formRecoveryMarker && data.event === "event" && data.payload?.event === "validate") {
    window.setTimeout(finishFormRecovery, 0)
  }
  return result
}

if (window.location.pathname === "/issues/3199") {
  const root = document.querySelector("[data-phx-main]")
  const transition = document.querySelector("#root-remove-transition")?.getAttribute("phx-remove")
  if (root && transition) root.setAttribute("phx-remove", transition)
}

const finishPendingNavigation = () => {
  document.querySelector("#scalive-navigation-pending")?.remove()
}

const waitForRootJoin = (previousMain) => {
  const marker = document.createElement("span")
  marker.id = "scalive-navigation-pending"
  marker.hidden = true
  marker.className = "phx-change-loading"
  document.body.append(marker)

  const wait = () => {
    const main = liveSocket.main
    if (main && main !== previousMain && main.isConnected() && !main.isJoinPending()) {
      finishPendingNavigation()
    } else {
      window.setTimeout(wait, 0)
    }
  }
  wait()
}

const queuePatchAfterRootJoin = (main, targetHref) => {
  if (main.scalivePendingPatch) return

  const isConnected = main.isConnected
  const pushWithReply = main.pushWithReply
  const targetState = window.history.state
  window.history.replaceState(targetState, "", main.href)
  main.scalivePendingPatch = true
  main.isConnected = () => true
  main.pushWithReply = function (refGenerator, event, payload) {
    if (event !== "live_patch") return pushWithReply.call(this, refGenerator, event, payload)

    return new Promise((resolve) => {
      const push = () => {
        if (main.isJoinPending()) {
          window.setTimeout(push, 0)
        } else {
          main.isConnected = isConnected
          main.pushWithReply = pushWithReply
          main.scalivePendingPatch = false
          window.history.replaceState(targetState, "", targetHref)
          pushWithReply.call(main, refGenerator, event, payload).then(resolve)
        }
      }
      push()
    })
  }
}

window.addEventListener("phx:navigate", (event) => {
  console.log("navigate event", JSON.stringify(event.detail))

  if (!event.detail.pop) return
  if (!window.location.pathname.startsWith("/issues/3529") && !window.location.pathname.startsWith("/navigation/")) return

  const main = liveSocket.main
  if (!event.detail.patch) waitForRootJoin(main)
  else if (main?.isJoinPending() && !main.isConnected()) queuePatchAfterRootJoin(main, event.detail.href)
})

window.addEventListener("reset", () => {
  document.querySelectorAll("[phx-feedback-for]").forEach((el) => {
    el.classList.add("phx-no-feedback")
  })
})

window.addEventListener("phx:e2e:console-log", (event) => {
  console.log(event.detail.message)
})
