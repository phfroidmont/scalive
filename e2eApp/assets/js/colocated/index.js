export const hooks = {
  ".PhoneNumber": {
    mounted() {
      this.el.addEventListener("input", () => {
        const match = this.el.value.replace(/\D/g, "").match(/^(\d{3})(\d{3})(\d{4})$/)
        if (match) {
          this.el.value = `${match[1]}-${match[2]}-${match[3]}`
        }
      })
    }
  },
  ".Runtime": {
    mounted() {
      this.js().show(this.el)
    }
  }
}

export default {
  js_exec(liveSocket) {
    window.addEventListener("phx:js:exec", (event) => {
      const cmd = event?.detail?.cmd
      if (typeof cmd === "string" && liveSocket.main) {
        liveSocket.execJS(liveSocket.main.el, cmd)
      }
    })
  }
}
