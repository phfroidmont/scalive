// docs:start quick-start-browser
const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const params = csrfToken ? { _csrf_token: csrfToken } : {}

const liveSocket = new LiveView.LiveSocket("/live", Phoenix.Socket, { params })
liveSocket.connect()

window.liveSocket = liveSocket
// docs:end quick-start-browser
