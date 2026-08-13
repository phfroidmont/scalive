import assert from "node:assert/strict"
import test from "node:test"

import { createBrowserInteropHook, readCopyRequest } from "./browser-interop.js"

function mountedHook(clipboard) {
  let handler
  const pushed = []
  const hook = {
    ...createBrowserInteropHook(clipboard),
    handleEvent(_name, nextHandler) {
      handler = nextHandler
    },
    async pushEvent(name, payload) {
      pushed.push({ name, payload })
    },
  }
  hook.mounted()
  return { handler: (payload) => handler(payload), hook, pushed }
}

test("validates browser copy request boundaries", () => {
  assert.deepEqual(readCopyRequest({ requestId: "copy-1", text: "sample" }), {
    requestId: "copy-1",
    text: "sample",
  })
  assert.equal(readCopyRequest({ requestId: "", text: "sample" }), undefined)
  assert.equal(readCopyRequest({ requestId: "x".repeat(65), text: "sample" }), undefined)
  assert.equal(readCopyRequest({ requestId: "copy-1", text: "x".repeat(4097) }), undefined)
})

test("writes valid text and returns a correlated result", async () => {
  const writes = []
  const { handler, pushed } = mountedHook({
    async writeText(text) {
      writes.push(text)
    },
  })
  await handler({ requestId: "copy-2", text: "sample" })
  assert.deepEqual(writes, ["sample"])
  assert.deepEqual(pushed, [
    { name: "browser-copy-result", payload: { requestId: "copy-2", ok: true } },
  ])
})

test("reports clipboard failure without exposing the text", async () => {
  const { handler, pushed } = mountedHook({
    async writeText() {
      throw new Error("denied")
    },
  })
  await handler({ requestId: "copy-3", text: "private" })
  assert.deepEqual(pushed, [
    { name: "browser-copy-result", payload: { requestId: "copy-3", ok: false } },
  ])
  assert.equal(JSON.stringify(pushed).includes("private"), false)
})

test("does not return a result after destruction", async () => {
  let finishWrite
  const { handler, hook, pushed } = mountedHook({
    writeText() {
      return new Promise((resolve) => {
        finishWrite = resolve
      })
    },
  })
  const operation = handler({ requestId: "copy-4", text: "sample" })
  hook.destroyed()
  finishWrite()
  await operation
  assert.deepEqual(pushed, [])
})
