const BlurFeedback = {
  mounted() {
    this.onFocusOut = (event) => {
      const next = event.relatedTarget
      if (next instanceof Node && this.el.contains(next)) return

      const close = this.el.querySelector("#choice-close")
      close?.click()
    }

    this.el.addEventListener("focusout", this.onFocusOut)
  },

  destroyed() {
    this.el.removeEventListener("focusout", this.onFocusOut)
  }
}

export default BlurFeedback
