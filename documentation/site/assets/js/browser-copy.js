// docs:start browser-integration-copy-module
const maxRequestIdLength = 64
const maxTextLength = 4096

export function readCopyRequest(payload) {
  const requestId = typeof payload?.requestId === "string" ? payload.requestId : ""
  const text = typeof payload?.text === "string" ? payload.text : undefined
  if (
    requestId.length === 0 ||
    requestId.length > maxRequestIdLength ||
    text === undefined ||
    text.length > maxTextLength
  ) return undefined
  return { requestId, text }
}

export async function copyRequestedText(payload, clipboard = globalThis.navigator?.clipboard) {
  const request = readCopyRequest(payload)
  let ok = false
  if (request && clipboard?.writeText) {
    try {
      await clipboard.writeText(request.text)
      ok = true
    } catch {
      ok = false
    }
  }
  return { requestId: request?.requestId ?? "", ok }
}
// docs:end browser-integration-copy-module
