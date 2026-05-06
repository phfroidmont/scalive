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
  QueuedUploaderHook: {
    mounted() {
      const maxConcurrency = Number.parseInt(this.el.dataset.maxConcurrency || "3", 10)
      let filesRemaining = []
      let queuedSignature = null

      const queueFiles = (event) => {
        event.preventDefault()
        event.stopPropagation()
        event.stopImmediatePropagation()

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

      this.el.addEventListener("input", queueFiles, true)
      this.el.addEventListener("change", queueFiles, true)

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
  }
}

let liveSocket = new LiveSocket("/live", Socket, {
  reloadJitterMin: 50,
  reloadJitterMax: window.location.pathname === "/errors" ? 50 : 500,
  maxReloads: 5,
  failsafeJitter: 1000,
  rejoinAfterMs: () => 50,
  params: liveSocketParams,
  hooks
})

liveSocket.connect()
window.liveSocket = liveSocket
colocated.js_exec(liveSocket)

window.addEventListener("phx:navigate", (event) => {
  console.log("navigate event", JSON.stringify(event.detail))
})

window.addEventListener("reset", () => {
  document.querySelectorAll("[phx-feedback-for]").forEach((el) => {
    el.classList.add("phx-no-feedback")
  })
})

window.addEventListener("phx:e2e:console-log", (event) => {
  console.log(event.detail.message)
})
