{%
title = "Guides"
description = "Task-oriented guidance for building and operating Scalive applications."
order = 0
section = guides
%}

## Solve a Task {#solve-a-task}

Guides provide focused, task-oriented steps for common Scalive development and
operations work. For an ordered introduction to the framework, start with
[Learn](../learn/index.md#start-here). Each guide declares its prerequisites so
you can also enter directly from search or the API reference.

- [Phoenix LiveView concepts in Scalive](phoenix-live-view-orientation.md) maps
  Phoenix concepts to Scalive's Scala-first API without assuming complete parity.

### Interfaces And Input {#interfaces-and-input}

- [HTML and event bindings](html-dsl-and-event-bindings.md) builds typed
  trees, sets attributes, binds messages, keys repeated content, and composes
  portal and focus-management helpers.
- [Typed forms and validation](typed-forms-and-validation.md) decodes rooted
  browser input into domain values with recovery and path-specific feedback.
- [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) combines
  checked actions, bounded CSRF-protected decoding, trigger-action handoff, and
  HTTP-to-Live flash.
- [File uploads](uploads-and-consumption.md) validates bounded files and chooses
  in-memory, hosted-writer, or external destination ownership.

### Routing And Application Structure {#routing-and-application-structure}

- [Routes, parameters, and navigation](routes-and-navigation.md) decodes typed
  URL state and chooses checked patch, navigation, replace, and redirect behavior.
- [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md)
  composes document shells, route groups, and typed pre-mount policy.
- [Authentication and sessions](authentication.md) combines ordinary HTTP login
  and reset with opaque sessions and protected LiveView mounts.
- [Nested LiveViews](nested-liveviews.md) embeds an independently mounted child
  with explicit socket, navigation, crash, and sticky-lifecycle ownership.

### State, Services, And Components {#state-services-and-components}

- [Services and dependency injection](services-and-zlayer-injection.md)
  constructor-injects application capabilities while keeping socket state
  connection-local.
- [Stateful components and communication](components-and-communication.md)
  isolates local state, maps typed outputs, and owns component-local features.
- [Streams and collection updates](streams-and-collection-updates.md) separates
  durable collection state from targeted, bounded DOM operations.

### Async And Lifecycle {#async-and-lifecycle}

- [Asynchronous work and subscriptions](async-work-and-subscriptions.md) owns finite
  tasks and long-lived message streams with typed keys and lifecycle cleanup.
- [Lifecycle hooks](lifecycle-hooks.md) applies ordered static or dynamic policy
  to root and component lifecycle stages.
- [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md) owns flash,
  document titles, connection feedback, and post-render observation.

### Browser Integration {#browser-integration}

- [Browser commands, events, and hooks](browser-integration.md) composes client
  effects and implements validated, registered JavaScript hooks.

### Testing And Troubleshooting {#testing-and-troubleshooting}

- [Testing LiveViews](testing.md) covers public disconnected and connected
  server-side tests, then identifies behavior that requires a real browser.
- [Troubleshooting](troubleshooting.md) diagnoses startup, asset, socket, CSRF,
  crash, and reconnect failures.

### Assets And Operations {#assets-and-operations}

- [Client setup and static assets](static-assets-and-client-setup.md) bundles
  the Phoenix client, serves tracked digested assets, and reacts to static
  changes and connection parameters.
- [Configuration](configuration.md) defines the current framework and
  application-owned configuration contract.
- [Deployment](deployment.md) runs the current application behind TLS and a
  WebSocket-capable edge without inventing unsupported packaging or clustering.
