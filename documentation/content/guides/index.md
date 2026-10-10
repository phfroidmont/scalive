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
- [Typed forms and validation](typed-forms-and-validation.md) defines a form,
  validates it as the visitor types, and decodes a valid submission into a
  domain value.
- [Form controls](form-controls.md) renders textareas, selects, checkboxes,
  custom inputs, and several submit buttons.
- [Form feedback and recovery](form-feedback-and-recovery.md) chooses when
  errors appear and restores a form after a reconnect.
- [Repeated form rows](repeated-form-rows.md) lets visitors add, remove, and
  reorder groups of fields.
- [Saving forms](saving-forms.md) tracks unsaved changes and saves in the
  background.
- [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) combines
  checked actions, bounded CSRF-protected decoding, trigger-action handoff, and
  HTTP-to-Live flash.
- [File uploads](uploads-and-consumption.md) validates bounded files and chooses
  in-memory, hosted-writer, or external destination ownership.

### Routing And Application Structure {#routing-and-application-structure}

- [Routes, parameters, and navigation](routes-and-navigation.md) decodes typed
  URL state and chooses checked patch, navigation, replace, and redirect behavior.
- [Layouts](layouts.md) renders the HTML document and wraps pages in shared
  navigation.
- [Live sessions and mount aspects](live-sessions-and-mount-aspects.md) groups
  routes and derives typed context, such as the current user, before mount.
- [Services and dependency injection](services-and-zlayer-injection.md)
  constructor-injects application capabilities while keeping socket state
  connection-local.
- [Authentication and sessions](authentication.md) combines ordinary HTTP login
  and logout with opaque sessions and protected LiveView mounts.

### State And Components {#state-and-components}

- [Stateful components and communication](components-and-communication.md)
  isolates local state, maps typed outputs, and owns component-local features.
- [Nested LiveViews](nested-liveviews.md) embeds an independently mounted child
  with explicit socket, navigation, crash, and sticky-lifecycle ownership.
- [Streams and collection updates](streams-and-collection-updates.md) separates
  durable collection state from targeted, bounded DOM operations.

### Async And Lifecycle {#async-and-lifecycle}

- [Asynchronous work](async-work.md) runs a finite task, such as a slow query,
  and renders its loading, failure, and cancelled states.
- [Subscriptions](subscriptions.md) runs a stream of messages, such as a timer
  or an event feed, while a page or component stays connected.
- [Connected resources](connected-resources.md) acquires a registration or
  handle when a page connects and releases it when the page closes.
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

- [Testing LiveViews](testing.md) tests interactions, forms, routes, and the
  first HTML response, then identifies behavior that requires a real browser.
- [Connected test reference](connected-test-reference.md) lists the exact
  contracts of the test helpers, reconnect admission, and origin checks.
- [Troubleshooting](troubleshooting.md) diagnoses startup, asset, socket, CSRF,
  crash, and reconnect failures.

### Assets And Operations {#assets-and-operations}

- [Configuration](configuration.md) configures transport security, route-level
  lifecycle observation, and setting ownership.
- [Lifecycle observability](lifecycle-observability.md) publishes fixed ZIO
  metrics and defines safe logging, tracing, labels, and coverage boundaries.
- [Deployment](deployment.md) packages and operates a Scalive JVM application
  behind HTTPS and a WebSocket-capable edge.
