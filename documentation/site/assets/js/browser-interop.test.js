import assert from "node:assert/strict"
import test from "node:test"

import { copyRequestedText, readCopyRequest } from "./browser-copy.js"
import { createBrowserInteropHook } from "./browser-interop.js"

function mountedHook(copyModule) {
  let handler
  const pushed = []
  const shown = []
  const failedNotice = { name: "failed notice" }
  const hook = {
    ...createBrowserInteropHook(() => copyModule),
    el: {
      querySelector: (selector) =>
        selector === "[data-browser-hook-failed]" ? failedNotice : undefined,
    },
    handleEvent(_name, nextHandler) {
      handler = nextHandler
    },
    async pushEvent(name, payload) {
      pushed.push({ name, payload })
    },
    js() {
      return { show: (element) => shown.push(element) }
    },
  }
  hook.mounted()
  return { failedNotice, handler: (payload) => handler(payload), hook, pushed, shown }
}

function loadedModule(clipboard) {
  return Promise.resolve({ copyRequestedText: (payload) => copyRequestedText(payload, clipboard) })
}

async function withSilencedConsoleError(run) {
  const original = console.error
  const logged = []
  console.error = (...values) => logged.push(values)
  try {
    await run()
  } finally {
    console.error = original
  }
  return logged
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
  const { handler, pushed } = mountedHook(
    loadedModule({
      async writeText(text) {
        writes.push(text)
      },
    }),
  )
  await handler({ requestId: "copy-2", text: "sample" })
  assert.deepEqual(writes, ["sample"])
  assert.deepEqual(pushed, [
    { name: "browser-copy-result", payload: { requestId: "copy-2", ok: true } },
  ])
})

test("reports clipboard failure without exposing the text", async () => {
  const { handler, pushed } = mountedHook(
    loadedModule({
      async writeText() {
        throw new Error("denied")
      },
    }),
  )
  await handler({ requestId: "copy-3", text: "private" })
  assert.deepEqual(pushed, [
    { name: "browser-copy-result", payload: { requestId: "copy-3", ok: false } },
  ])
  assert.equal(JSON.stringify(pushed).includes("private"), false)
})

test("does not return a result after destruction", async () => {
  let finishWrite
  const { handler, hook, pushed } = mountedHook(
    loadedModule({
      writeText() {
        return new Promise((resolve) => {
          finishWrite = resolve
        })
      },
    }),
  )
  const operation = handler({ requestId: "copy-4", text: "sample" })
  await new Promise((resolve) => setImmediate(resolve))
  hook.destroyed()
  finishWrite()
  await operation
  assert.deepEqual(pushed, [])
})

test("handles a request that arrives while the module is loading", async () => {
  const writes = []
  let finishLoading
  const loading = new Promise((resolve) => {
    finishLoading = resolve
  })
  const { handler, pushed } = mountedHook(loading)
  const operation = handler({ requestId: "copy-5", text: "sample" })
  finishLoading({
    copyRequestedText: (payload) =>
      copyRequestedText(payload, {
        async writeText(text) {
          writes.push(text)
        },
      }),
  })
  await operation
  assert.deepEqual(writes, ["sample"])
  assert.deepEqual(pushed, [
    { name: "browser-copy-result", payload: { requestId: "copy-5", ok: true } },
  ])
})

test("does nothing when the module loads after destruction", async () => {
  const writes = []
  let finishLoading
  const loading = new Promise((resolve) => {
    finishLoading = resolve
  })
  const { handler, hook, pushed } = mountedHook(loading)
  const operation = handler({ requestId: "copy-6", text: "sample" })
  hook.destroyed()
  finishLoading({
    copyRequestedText: (payload) =>
      copyRequestedText(payload, {
        async writeText(text) {
          writes.push(text)
        },
      }),
  })
  await operation
  assert.deepEqual(writes, [])
  assert.deepEqual(pushed, [])
})

test("shows the failed notice and logs when the module cannot load", async () => {
  const failure = new Error("chunk missing")
  let mounted
  const logged = await withSilencedConsoleError(async () => {
    mounted = mountedHook(Promise.reject(failure))
    await mounted.handler({ requestId: "copy-7", text: "sample" })
    await new Promise((resolve) => setImmediate(resolve))
  })
  assert.deepEqual(mounted.shown, [mounted.failedNotice])
  assert.deepEqual(mounted.pushed, [])
  assert.equal(logged.length, 1)
  assert.equal(logged[0].includes(failure), true)
})

test("leaves a destroyed hook's element alone when the module cannot load", async () => {
  let mounted
  await withSilencedConsoleError(async () => {
    let failLoading
    const loading = new Promise((_resolve, reject) => {
      failLoading = reject
    })
    mounted = mountedHook(loading)
    mounted.hook.destroyed()
    failLoading(new Error("chunk missing"))
    await new Promise((resolve) => setImmediate(resolve))
  })
  assert.deepEqual(mounted.shown, [])
})
