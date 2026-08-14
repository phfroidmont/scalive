{%
title = "Guides"
description = "Task-oriented guidance for building and operating Scalive applications."
order = 0
section = guides
%}

## Solve a Task {#solve-a-task}

Guides provide focused, task-oriented steps for common Scalive development and
operations work. For an ordered introduction to the framework, start with
[Learn](../learn/index.md#start-here).

- [Phoenix LiveView concepts in Scalive](phoenix-live-view-orientation.md) maps
  Phoenix concepts to Scalive's Scala-first API without assuming complete parity.

### Foundations {#foundations}

- [HTML and event bindings](html-dsl-and-event-bindings.md) builds typed
  trees, sets attributes, binds messages, and keys repeated content.
- [Client setup and static assets](static-assets-and-client-setup.md) bundles
  the Phoenix client and serves tracked, digested assets.
- [Routes, parameters, and navigation](routes-and-navigation.md) decodes typed
  URL state and chooses checked patch, navigation, replace, and redirect behavior.
- [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md)
  composes document shells, route groups, and typed pre-mount policy.

### Building Applications {#building-applications}

- [Services and dependency injection](services-and-zlayer-injection.md)
  constructor-injects application capabilities while keeping socket state
  connection-local.
- [Typed forms and validation](typed-forms-and-validation.md) decodes rooted
  browser input into domain values with path-specific feedback.
- [Authentication and sessions](authentication.md) combines ordinary HTTP login
  and reset with opaque sessions and protected LiveView mounts.
- [Stateful components and communication](components-and-communication.md)
  isolates local state and maps typed component outputs into owner messages.

### Advanced Features {#advanced-features}

- [Streams and collection updates](streams-and-collection-updates.md) separates
  durable collection state from targeted, bounded DOM operations.
- [Asynchronous work and subscriptions](async-work-and-subscriptions.md) owns finite
  tasks and long-lived message streams with typed keys and lifecycle cleanup.
- [File uploads](uploads-and-consumption.md) validates bounded files and makes
  destination-resource ownership explicit.
- [Browser commands, events, and hooks](browser-integration.md) composes client
  effects and exchanges validated typed payloads with focused hooks.
- [Flash messages, page titles, and lifecycle UX](flash-title-and-lifecycle-ux.md)
  communicates
  notification, document-title, connection, and after-render state.

### Quality and Operations {#quality-and-operations}

- [Testing LiveViews](testing.md) separates public disconnected tests from
  application-owned connected and browser tests.
- [Troubleshooting](troubleshooting.md) diagnoses startup, asset, socket, CSRF,
  crash, and reconnect failures.
