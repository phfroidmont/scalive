import assert from "node:assert/strict"
import test from "node:test"

import {
  createLiveTraceAdapter,
  createTraceSession,
  sanitizeProtocol,
  wrapDecoder,
  wrapEncoder,
} from "./phoenix-live-view-1.1.28.js"

test("encoder forwards the exact serialized value before observing it", async () => {
  const encoded = new ArrayBuffer(4)
  const message = { topic: "lv:example", event: "event", payload: {}, ref: "2", join_ref: "1" }
  const calls = []
  const wrapped = wrapEncoder((_message, callback) => callback(encoded), (observedMessage, observed) => {
    calls.push(["observe", observedMessage, observed])
  })

  wrapped(message, (value) => calls.push(["callback", value]))
  await Promise.resolve()

  assert.equal(calls[0][0], "callback")
  assert.strictEqual(calls[0][1], encoded)
  assert.strictEqual(calls[1][1], message)
  assert.strictEqual(calls[1][2], encoded)
})

test("decoder forwards the exact decoded object", () => {
  const decoded = { topic: "lv:example", event: "phx_reply", payload: {}, ref: "2", join_ref: "1" }
  const calls = []
  const wrapped = wrapDecoder((_raw, callback) => callback(decoded), {
    before(message) {
      calls.push(["before", message])
    },
    after(message, _raw, succeeded) {
      calls.push(["after", message, succeeded])
    },
  })

  wrapped("raw-frame", (message) => calls.push(["callback", message]))

  assert.deepEqual(calls.map(([name]) => name), ["before", "callback", "after"])
  assert.ok(calls.every(([, message]) => message === decoded))
  assert.equal(calls[2][2], true)
})

test("decoder reports a thrown callback and still invokes the after observer", () => {
  const decoded = { topic: "lv:example", event: "phx_reply", payload: {}, ref: "2", join_ref: "1" }
  const calls = []
  const wrapped = wrapDecoder((_raw, callback) => callback(decoded), {
    before() {
      calls.push(["before"])
    },
    after(_message, _raw, succeeded) {
      calls.push(["after", succeeded])
    },
  })

  assert.throws(
    () => wrapped("raw-frame", () => {
      calls.push(["callback"])
      throw new Error("callback failed")
    }),
    /callback failed/,
  )

  assert.deepEqual(calls, [["before"], ["callback"], ["after", false]])
})

test("adapter emits a correlated terminal record after successful inbound processing", async () => {
  const batches = []
  const adapter = createLiveTraceAdapter()
  adapter.hook.mounted.call({
    el: {
      dataset: {
        liveTraceObservedTopic: "lv:example",
        liveTraceEnabled: "true",
        liveTraceBrowserEvent: "trace-records",
      },
    },
    pushEvent(_event, payload) {
      batches.push(payload.records)
    },
  })
  const decoded = { topic: "lv:example", event: "phx_reply", payload: {}, ref: "2", join_ref: "1" }
  const wrapped = wrapDecoder((_raw, callback) => callback(decoded), {
    before: adapter.beginInbound,
    after: adapter.endInbound,
  })

  wrapped("raw-frame", () => {})
  await Promise.resolve()

  const records = batches.flat()
  assert.deepEqual(records.map((record) => record.stage), ["InboundFrame", "InboundProcessed"])
  const terminal = records[1]
  assert.equal(terminal.summary, "Inbound protocol frame processed")
  assert.equal(terminal.topic, decoded.topic)
  assert.equal(terminal.joinReference, decoded.join_ref)
  assert.equal(terminal.messageReference, decoded.ref)
  assert.equal(terminal.operationSequence, Number(decoded.ref))
})

test("adapter omits the terminal record after a thrown callback and clears inbound state", async () => {
  const batches = []
  const adapter = createLiveTraceAdapter()
  adapter.hook.mounted.call({
    el: {
      dataset: {
        liveTraceObservedTopic: "lv:example",
        liveTraceEnabled: "true",
        liveTraceBrowserEvent: "trace-records",
      },
    },
    pushEvent(_event, payload) {
      batches.push(payload.records)
    },
  })
  const decoded = { topic: "lv:example", event: "phx_reply", payload: {}, ref: "2", join_ref: "1" }
  const wrapped = wrapDecoder((_raw, callback) => callback(decoded), {
    before: adapter.beginInbound,
    after: adapter.endInbound,
  })
  const previousMutationObserver = globalThis.MutationObserver
  globalThis.MutationObserver = class {
    observe() {}
    takeRecords() { return [] }
    disconnect() {}
  }

  try {
    assert.throws(() => wrapped("raw-frame", () => {
      throw new Error("callback failed")
    }), /callback failed/)

    const container = { id: "example" }
    adapter.dom.onPatchStart(container)
    adapter.dom.onPatchEnd(container)
    await Promise.resolve()
  } finally {
    if (previousMutationObserver === undefined) delete globalThis.MutationObserver
    else globalThis.MutationObserver = previousMutationObserver
  }

  const records = batches.flat()
  assert.equal(records.some((record) => record.stage === "InboundProcessed"), false)
  const patch = records.find((record) => record.stage === "DomPatch")
  assert.equal(patch.joinReference, null)
  assert.equal(patch.messageReference, null)
})

test("protocol sanitization removes secrets and binary content", () => {
  const sanitized = sanitizeProtocol({
    topic: "lv:example",
    event: "event",
    join_ref: "1",
    ref: "2",
    payload: {
      event: "binding-id",
      password: "password-secret",
      _csrf_token: "csrf-secret",
      value: "public form value",
      bytes: new Uint8Array([1, 2, 3]),
    },
  })
  const encoded = JSON.stringify(sanitized)

  assert.equal(sanitized.topic, "lv:example")
  assert.equal(sanitized.payload.event, "binding-id")
  assert.ok(!encoded.includes("password-secret"))
  assert.ok(!encoded.includes("csrf-secret"))
  assert.ok(!encoded.includes("public form value"))
  assert.ok(!encoded.includes("1,2,3"))
  assert.deepEqual(sanitized.payload.bytes, { byteLength: 3, content: "[redacted]" })
})

test("protocol sanitization redacts strings under neutral keys", () => {
  const sanitized = sanitizeProtocol({
    topic: "lv:example",
    event: "event",
    join_ref: "1",
    ref: "2",
    payload: {
      label: "Increase counter",
      nested: { result: "Counter is 1" },
      count: 1,
      active: true,
      missing: null,
      status: "ok",
    },
  })

  assert.equal(sanitized.payload.label, "[redacted]")
  assert.equal(sanitized.payload.nested.result, "[redacted]")
  assert.equal(sanitized.payload.count, 1)
  assert.equal(sanitized.payload.active, true)
  assert.equal(sanitized.payload.missing, null)
  assert.equal(sanitized.payload.status, "ok")
})

test("DOM mutation evidence omits text and attribute values", async () => {
  const batches = []
  const adapter = createLiveTraceAdapter()
  const hook = {
    el: {
      dataset: {
        liveTraceObservedTopic: "lv:example",
        liveTraceEnabled: "true",
        liveTraceBrowserEvent: "trace-records",
      },
    },
    pushEvent(_event, payload) {
      batches.push(payload.records)
    },
  }
  adapter.hook.mounted.call(hook)

  const previousNode = globalThis.Node
  const previousMutationObserver = globalThis.MutationObserver
  globalThis.Node = { TEXT_NODE: 3, ELEMENT_NODE: 1 }
  globalThis.MutationObserver = class {
    observe() {}
    takeRecords() {
      const element = {
        nodeType: 1,
        id: "example",
        tagName: "DIV",
        getAttribute: () => "attribute-secret",
      }
      return [
        {
          type: "characterData",
          target: { nodeType: 3, data: "text-secret", parentNode: element },
          oldValue: "old-text-secret",
        },
        {
          type: "attributes",
          target: element,
          attributeName: "title",
          oldValue: "old-attribute-secret",
        },
      ]
    }
    disconnect() {}
  }

  try {
    const container = { id: "example" }
    adapter.dom.onPatchStart(container)
    adapter.dom.onPatchEnd(container)
    await Promise.resolve()
  } finally {
    if (previousNode === undefined) delete globalThis.Node
    else globalThis.Node = previousNode
    if (previousMutationObserver === undefined) delete globalThis.MutationObserver
    else globalThis.MutationObserver = previousMutationObserver
  }

  const encoded = JSON.stringify(batches.flat())
  assert.ok(!encoded.includes("text-secret"))
  assert.ok(!encoded.includes("attribute-secret"))
  const mutations = batches.flat().find((record) => record.stage === "DomDiff").protocol.mutations
  assert.deepEqual(mutations, [
    { kind: "text", target: "div#example" },
    { kind: "attribute", target: "div#example", name: "title" },
  ])
})

test("multiple hooks observe one topic independently", async () => {
  const adapter = createLiveTraceAdapter()
  const firstBatches = []
  const secondBatches = []
  const makeHook = (batches) => ({
    el: {
      dataset: {
        liveTraceObservedTopic: "lv:example",
        liveTraceEnabled: "true",
        liveTraceBrowserEvent: "trace-records",
      },
    },
    pushEvent(_event, payload) {
      batches.push(payload.records)
    },
  })
  const first = makeHook(firstBatches)
  const second = makeHook(secondBatches)
  adapter.hook.mounted.call(first)
  adapter.hook.mounted.call(second)

  const message = { topic: "lv:example", event: "event", payload: {}, ref: "2", join_ref: "1" }
  adapter.observeOutbound(message)
  await Promise.resolve()
  assert.equal(firstBatches.length, 1)
  assert.equal(secondBatches.length, 1)

  adapter.hook.destroyed.call(first)
  adapter.observeOutbound({ ...message, ref: "3" })
  await Promise.resolve()
  assert.equal(firstBatches.length, 1)
  assert.equal(secondBatches.length, 2)
})

test("trace sessions use page-lifetime random UUIDs", () => {
  assert.equal(
    createTraceSession({ randomUUID: () => "01234567-89ab-cdef-0123-456789abcdef" }),
    "01234567-89ab-cdef-0123-456789abcdef",
  )
})
