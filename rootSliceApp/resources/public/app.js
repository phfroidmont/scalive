window.liveSocketOptions = {
  hooks: {
    OptionsHook: {
      mounted() {
        this.el.textContent = "hooked"
      },
    },
  },
  params: () => ({ locale: "fr" }),
}
