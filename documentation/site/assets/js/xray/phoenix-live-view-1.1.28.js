import { Serializer, Socket } from "phoenix"

export const traceSessionParameter = "_scalive_trace_session"
export const expectedLiveViewVersion = "1.1.28"

const redacted = "[redacted]"
const sensitiveFragments = [
  "authorization",
  "claim",
  "cookie",
  "credential",
  "csrf",
  "flash",
  "password",
  "secret",
  "session",
  "static",
  "token",
  "upload",
  "url",
  "redirect",
]

function resolveParams(params) {
  return typeof params === "function" ? params() : params || {}
}

function isSensitive(name) {
  const normalized = name.toLowerCase()
  return sensitiveFragments.some((fragment) => normalized.includes(fragment))
}

const structuralStringFields = new Set(["event", "kind", "status", "type"])
const structuralStringPattern = /^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,63}$/

function sanitizeValue(value, fieldName = null) {
  if (value === null || typeof value === "boolean" || typeof value === "number") return value
  if (typeof value === "string") {
    if (
      fieldName &&
      !isSensitive(fieldName) &&
      structuralStringFields.has(fieldName) &&
      structuralStringPattern.test(value)
    ) return value
    return redacted
  }
  if (value instanceof ArrayBuffer) return { byteLength: value.byteLength, content: redacted }
  if (ArrayBuffer.isView(value)) return { byteLength: value.byteLength, content: redacted }
  if (Array.isArray(value)) return value.map((item) => sanitizeValue(item))
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([name, child]) => [name, sanitizeValue(child, name)]),
    )
  }
  return redacted
}

export function sanitizeProtocol(message) {
  return {
    joinReference: message.join_ref ?? null,
    messageReference: message.ref ?? null,
    topic: message.topic,
    event: message.event,
    payload: sanitizeValue(message.payload),
  }
}

export function createTraceSession(cryptoApi = globalThis.crypto) {
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID()
  const bytes = new Uint8Array(16)
  cryptoApi.getRandomValues(bytes)
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("")
}

export function wrapEncoder(encode, observer) {
  return (message, callback) =>
    encode(message, (encoded) => {
      callback(encoded)
      queueMicrotask(() => observer(message, encoded))
    })
}

export function wrapDecoder(decode, observer) {
  return (raw, callback) =>
    decode(raw, (message) => {
      observer.before(message, raw)
      let succeeded = false
      try {
        callback(message)
        succeeded = true
      } finally {
        observer.after(message, raw, succeeded)
      }
    })
}

export class LiveTraceSocket extends Socket {
  constructor(endpoint, options = {}) {
    const adapter = options.liveTraceAdapter
    const traceSession = options.liveTraceSession
    const transportParams = () => ({
      ...resolveParams(options.params),
      ...(traceSession ? { [traceSessionParameter]: traceSession } : {}),
    })
    super(endpoint, {
      ...options,
      params: transportParams,
      encode: adapter
        ? wrapEncoder(Serializer.encode.bind(Serializer), adapter.observeOutbound)
        : options.encode,
      decode: adapter
        ? wrapDecoder(Serializer.decode.bind(Serializer), {
            before: adapter.beginInbound,
            after: adapter.endInbound,
          })
        : options.decode,
    })
  }
}

function nodeDescription(node) {
  if (node.nodeType === Node.TEXT_NODE) return "text"
  if (node.nodeType !== Node.ELEMENT_NODE) return node.nodeName.toLowerCase()
  const id = node.id ? `#${node.id}` : ""
  return `${node.tagName.toLowerCase()}${id}`
}

function mutationSummary(mutation) {
  if (mutation.type === "characterData") {
    return {
      kind: "text",
      target: nodeDescription(mutation.target.parentNode || mutation.target),
    }
  }
  if (mutation.type === "attributes") {
    const name = mutation.attributeName
    return {
      kind: "attribute",
      target: nodeDescription(mutation.target),
      name: isSensitive(name) ? redacted : name,
    }
  }
  return {
    kind: "children",
    target: nodeDescription(mutation.target),
    added: Array.from(mutation.addedNodes, nodeDescription),
    removed: Array.from(mutation.removedNodes, nodeDescription),
  }
}

export function createLiveTraceAdapter() {
  const registrations = new Map()
  const sequences = new Map()
  const pending = new Map()
  const observers = new Map()
  let inboundMessage = null
  let flushScheduled = false

  function nextSequence(topic) {
    const sequence = (sequences.get(topic) || 0) + 1
    sequences.set(topic, sequence)
    return sequence
  }

  function operationSequence(message) {
    const reference = Number(message.ref)
    return Number.isSafeInteger(reference) ? reference : nextSequence(`${message.topic}:operation`)
  }

  function enqueue(topic, stage, summary, message, protocol = null) {
    if (!registrations.has(topic)) return
    const records = pending.get(topic) || []
    records.push({
      sequence: nextSequence(topic),
      topic,
      joinReference: message?.join_ref ?? null,
      messageReference: message?.ref ?? null,
      operationSequence: message ? operationSequence(message) : nextSequence(`${topic}:operation`),
      stage,
      summary,
      protocol,
    })
    pending.set(topic, records)
    if (!flushScheduled) {
      flushScheduled = true
      queueMicrotask(flush)
    }
  }

  function flush() {
    flushScheduled = false
    for (const [topic, records] of pending) {
      const topicRegistrations = registrations.get(topic)
      pending.delete(topic)
      if (topicRegistrations && records.length > 0) {
        for (const registration of topicRegistrations.values()) {
          registration.hook.pushEvent(registration.eventName, { records })
        }
      }
    }
  }

  function unregister(hook) {
    const topic = hook.__liveTraceObservedTopic
    if (!topic) return
    const topicRegistrations = registrations.get(topic)
    topicRegistrations?.delete(hook)
    if (topicRegistrations?.size === 0) registrations.delete(topic)
    hook.__liveTraceObservedTopic = null
  }

  function register(hook) {
    unregister(hook)
    const topic = hook.el.dataset.liveTraceObservedTopic
    hook.__liveTraceObservedTopic = topic
    if (topic && hook.el.dataset.liveTraceEnabled === "true") {
      const topicRegistrations = registrations.get(topic) || new Map()
      topicRegistrations.set(hook, {
        hook,
        eventName: hook.el.dataset.liveTraceBrowserEvent,
      })
      registrations.set(topic, topicRegistrations)
    }
  }

  const hook = {
    mounted() {
      register(this)
    },
    updated() {
      register(this)
    },
    destroyed() {
      unregister(this)
    },
  }

  function observeOutbound(message, _encoded) {
    if (!registrations.has(message.topic)) return
    if (message.event === "event") {
      enqueue(message.topic, "BrowserEvent", "Browser event sent", message)
    }
    enqueue(message.topic, "OutboundFrame", "Outbound protocol frame encoded", message, sanitizeProtocol(message))
  }

  function beginInbound(message, _raw) {
    inboundMessage = registrations.has(message.topic) ? message : null
    if (inboundMessage) {
      enqueue(message.topic, "InboundFrame", "Inbound protocol frame decoded", message, sanitizeProtocol(message))
    }
  }

  function endInbound(_message, _raw, succeeded) {
    try {
      if (succeeded && inboundMessage) {
        enqueue(
          inboundMessage.topic,
          "InboundProcessed",
          "Inbound protocol frame processed",
          inboundMessage,
        )
      }
    } finally {
      inboundMessage = null
    }
  }

  function topicForContainer(container) {
    const topic = container?.id ? `lv:${container.id}` : null
    return topic && registrations.has(topic) ? topic : null
  }

  const dom = {
    onPatchStart(container) {
      const topic = topicForContainer(container)
      if (!topic) return
      const observer = new MutationObserver(() => {})
      observer.observe(container, {
        subtree: true,
        childList: true,
        characterData: true,
        characterDataOldValue: true,
        attributes: true,
        attributeOldValue: true,
      })
      observers.set(container, { observer, topic, message: inboundMessage })
      enqueue(topic, "DomPatch", "DOM patch started", inboundMessage)
    },
    onPatchEnd(container) {
      const active = observers.get(container)
      if (!active) return
      const mutations = active.observer.takeRecords().map(mutationSummary)
      active.observer.disconnect()
      observers.delete(container)
      enqueue(
        active.topic,
        "DomDiff",
        mutations.length === 0 ? "DOM patch made no changes" : `DOM patch applied ${mutations.length} changes`,
        active.message,
        { mutations },
      )
    },
  }

  return { hook, dom, observeOutbound, beginInbound, endInbound }
}

export function assertLiveViewVersion(liveSocket) {
  const actual = liveSocket.version()
  if (actual !== expectedLiveViewVersion) {
    throw new Error(`X-ray adapter expects LiveView ${expectedLiveViewVersion}, received ${actual}`)
  }
}
