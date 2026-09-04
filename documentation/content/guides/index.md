{%
title = "Guides"
description = "Task-oriented guidance for building and operating Scalive applications."
order = 0
section = guides
%}

## Solve a Task {#solve-a-task}

Guides provide focused, task-oriented steps for common Scalive development and
operations work. For an ordered introduction to the framework, start with
[Learn](../learn/index.md#start-here). Each guide describes the starting state it
expects so you can also enter directly from search or the API reference.

### Orientation {#orientation}

- [Phoenix LiveView concepts in Scalive](phoenix-live-view-orientation.md) maps
  Phoenix concepts to Scalive's Scala-first API without assuming complete parity.

### Setup And Foundations {#setup-and-foundations}

- [Client setup and static assets](static-assets-and-client-setup.md) loads the
  packaged Phoenix clients or a custom bundle, serves an ordinary versioned tree
  or manifest-defined final paths, and reacts to static changes and connection
  parameters.

### Interfaces And Input {#interfaces-and-input}

- [HTML and event bindings](html-dsl-and-event-bindings.md) builds typed
  trees, composes token-list attributes, binds messages, keys repeated content,
  and uses portal and focus-management helpers.
- [Typed forms and validation](typed-forms-and-validation.md) decodes rooted
  browser input into domain values with recovery, stable repeated rows,
  path-specific feedback, and revision-bound save coordination.
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
- [Services and dependency injection](services-and-zlayer-injection.md)
  constructor-injects application capabilities while keeping socket state
  connection-local.
- [Authentication and sessions](authentication.md) combines ordinary HTTP login
  and reset with opaque sessions and protected LiveView mounts.

### State And Components {#state-and-components}

- [Stateful components and communication](components-and-communication.md)
  isolates local state, maps typed outputs, and owns component-local features.
- [Nested LiveViews](nested-liveviews.md) embeds an independently mounted child
  with explicit socket, navigation, crash, and sticky-lifecycle ownership.
- [Streams and collection updates](streams-and-collection-updates.md) separates
  durable collection state from targeted, bounded DOM operations.

### Async And Lifecycle {#async-and-lifecycle}

- [Asynchronous work, subscriptions, and connected resources](async-work-and-subscriptions.md)
  owns finite tasks, long-lived message streams, and other acquired resources
  with lifecycle cleanup.
- [Lifecycle hooks](lifecycle-hooks.md) guards complete connected turns and
  applies ordered static or dynamic policy to individual lifecycle stages.
- [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md) owns flash,
  document titles, connection feedback, and post-render observation.

### Browser Integration {#browser-integration}

- [Browser commands, events, and hooks](browser-integration.md) composes client
  effects and implements validated, registered JavaScript hooks.
- [Guard unsaved changes](navigation-guards.md) installs the framework browser
  runtime and confirms browser-initiated navigation while rendered state is dirty.

### Testing And Troubleshooting {#testing-and-troubleshooting}

- [Testing LiveViews](testing.md) covers public disconnected and connected
  server-side tests, then identifies behavior that requires a real browser.
- [Troubleshooting](troubleshooting.md) diagnoses startup, asset, socket, CSRF,
  crash, and reconnect failures.

### Assets And Operations {#assets-and-operations}

- [Configuration](configuration.md) configures transport security, route-level
  lifecycle observation, and setting ownership.
- [Lifecycle observability](lifecycle-observability.md) publishes fixed ZIO
  metrics and defines safe logging, tracing, labels, and coverage boundaries.
- [Deployment](deployment.md) packages and operates a Scalive JVM application
  behind HTTPS and a WebSocket-capable edge.
