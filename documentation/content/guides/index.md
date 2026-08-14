{%
title = "Guides"
description = "Task-oriented guidance for building and operating Scalive applications."
order = 0
section = guides
%}

## Solve A Task {#solve-a-task}

Guides provide focused, task-oriented steps for common Scalive development and
operations work. For an ordered introduction to the framework, start with
[Learn](../learn/index.md#start-here).

- [HTML DSL and event bindings](html-dsl-and-event-bindings.md) builds typed
  trees, sets attributes, binds messages, and keys repeated content.
- [Typed forms and validation](typed-forms-and-validation.md) decodes rooted
  browser input into domain values with path-specific feedback.
- [Components and communication](components-and-communication.md) isolates local
  state and maps typed component outputs into owner messages.
- [Services and ZLayer injection](services-and-zlayer-injection.md) constructor-injects
  application capabilities while keeping socket state connection-local.
- [JS commands, browser events, and hooks](browser-integration.md) composes
  client effects and exchanges validated typed payloads with focused hooks.
- [Streams and collection updates](streams-and-collection-updates.md) separates
  durable collection state from targeted, bounded DOM operations.
- [Uploads and consumption](uploads-and-consumption.md) validates bounded files
  and makes destination-resource ownership explicit.
- [Async work and subscriptions](async-work-and-subscriptions.md) owns finite
  tasks and long-lived message streams with typed keys and lifecycle cleanup.
- [Routes, parameters, and navigation](routes-and-navigation.md) decodes typed
  URL state and chooses checked patch, navigation, replace, and redirect behavior.
- [Layouts, sessions, and mount context](layouts-sessions-and-mount-aspects.md)
  composes document shells, route groups, and typed pre-mount policy.
- [Authentication](authentication.md) combines ordinary HTTP login and reset
  with opaque sessions and protected LiveView mounts.
- [Flash, title, and lifecycle UX](flash-title-and-lifecycle-ux.md) communicates
  notification, document-title, connection, and after-render state.
- [Static assets and client setup](static-assets-and-client-setup.md) bundles
  the Phoenix client and serves tracked, digested assets.
- [Testing LiveViews](testing.md) separates public disconnected tests from
  application-owned connected and browser tests.
- [Troubleshooting](troubleshooting.md) diagnoses startup, asset, socket, CSRF,
  crash, and reconnect failures.
- [Phoenix LiveView orientation](phoenix-live-view-orientation.md) maps Phoenix
  concepts to Scalive's Scala-first API without assuming complete parity.
