import assert from "node:assert/strict"
import test from "node:test"

import {
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
    after(message) {
      calls.push(["after", message])
    },
  })

  wrapped("raw-frame", (message) => calls.push(["callback", message]))

  assert.deepEqual(calls.map(([name]) => name), ["before", "callback", "after"])
  assert.ok(calls.every(([, message]) => message === decoded))
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
      value: "free-text-secret",
      bytes: new Uint8Array([1, 2, 3]),
    },
  })
  const encoded = JSON.stringify(sanitized)

  assert.equal(sanitized.topic, "lv:example")
  assert.equal(sanitized.payload.event, "binding-id")
  assert.ok(!encoded.includes("password-secret"))
  assert.ok(!encoded.includes("csrf-secret"))
  assert.ok(!encoded.includes("free-text-secret"))
  assert.ok(!encoded.includes("1,2,3"))
})

test("trace sessions use page-lifetime random UUIDs", () => {
  assert.equal(
    createTraceSession({ randomUUID: () => "01234567-89ab-cdef-0123-456789abcdef" }),
    "01234567-89ab-cdef-0123-456789abcdef",
  )
})
