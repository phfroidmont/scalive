{%
title = "Guides"
description = "Task-oriented guidance for building and operating Scalive applications."
order = 0
section = guides
%}

## Solve a task {#solve-a-task}

Guides provide focused, task-oriented steps for common Scalive development and
operations work. For an ordered introduction to the framework, start with
[Learn](../learn/index.md#start-here). Each guide describes the starting state it
expects so you can also enter directly from search or the API reference.

### Orientation {#orientation}

- [Phoenix LiveView concepts in Scalive](phoenix-live-view-orientation.md):
  find the Scalive equivalent of a Phoenix LiveView concept, callback, or
  function.

### Setup and foundations {#setup-and-foundations}

- [Client setup and static assets](static-assets-and-client-setup.md): load
  the Phoenix LiveView client, add your own JavaScript and CSS, and serve them
  from URLs that browsers can cache safely.
- [Configuration](configuration.md): set the signing secret, cookies, allowed
  WebSocket origins, and paths, and make startup fail when a setting is
  missing.

### Interfaces and input {#interfaces-and-input}

- [HTML and event bindings](html-dsl-and-event-bindings.md): build markup, show
  content conditionally, render lists, and send a message when the visitor
  clicks, types, or scrolls.
- [Typed forms and validation](typed-forms-and-validation.md): define a form,
  validate it as the visitor types, and turn a valid submission into a domain
  value.
- [Form controls](form-controls.md): render textareas, selects, checkboxes,
  custom inputs, and several submit buttons.
- [Form feedback and recovery](form-feedback-and-recovery.md): choose when
  errors appear and restore a form after a reconnect.
- [Repeated form rows](repeated-form-rows.md): let visitors add, remove, and
  reorder groups of fields.
- [Saving forms](saving-forms.md): track unsaved changes and save in the
  background.
- [Ordinary HTTP forms and redirects](http-forms-and-redirects.md): submit a
  form as a normal HTTP request, for example to log in or set a cookie, and
  redirect to a LiveView with a flash message.
- [File uploads](uploads-and-consumption.md): accept files, show their
  progress, and store them on your server or send them directly to external
  storage.

### Routing and application structure {#routing-and-application-structure}

- [Routes, parameters, and navigation](routes-and-navigation.md): keep filters
  and pagination in the URL, read typed path and query parameters, and move
  between pages.
- [Layouts](layouts.md): render the HTML document and wrap pages in shared
  navigation.
- [Live sessions and mount aspects](live-sessions-and-mount-aspects.md): group
  routes and load shared context, such as the current user, before a page
  mounts.
- [Services and dependency injection](services-and-zlayer-injection.md): give a
  LiveView a database or another application service, and keep shared state
  there rather than in the model.
- [Authentication and sessions](authentication.md): log visitors in and out,
  protect pages, and close a session's open pages when it ends.

### State and components {#state-and-components}

- [Stateful components and communication](components-and-communication.md):
  split a page into components that keep their own state and report events to
  the page.
- [Nested LiveViews](nested-liveviews.md): embed a LiveView in another page
  when it needs its own lifecycle, its own failure boundary, or to stay mounted
  across navigation.
- [Streams and collection updates](streams-and-collection-updates.md): show a
  large or growing list, such as a feed or a log, and send the browser only the
  rows that changed.

### Async and lifecycle {#async-and-lifecycle}

- [Asynchronous work](async-work.md): load slow data without blocking the page,
  and show its loading, failure, and cancelled states.
- [Subscriptions](subscriptions.md): update a page from a timer or an event
  feed while it stays connected.
- [Connected resources](connected-resources.md): open a registration or handle
  when a page connects and close it when the page closes.
- [Lifecycle hooks](lifecycle-hooks.md): apply one policy to every event,
  message, or render of a page, such as checking authorization before each
  event.
- [Flash messages, page titles, and connection status](flash-titles-and-connection-status.md):
  show a brief notice, keep the browser tab's title in sync with the page, and
  tell visitors when the page is offline.

### Browser integration {#browser-integration}

- [Browser commands, events, and hooks](browser-integration.md): run
  browser-only effects, push events to JavaScript, and use a JavaScript library
  from a LiveView.
- [Guard unsaved changes](navigation-guards.md): ask for confirmation before
  the visitor leaves a page with unsaved changes.

### Testing and troubleshooting {#testing-and-troubleshooting}

- [Testing LiveViews](testing.md): test clicks, forms, navigation, and the
  first HTML response, and know when a test needs a real browser.
- [Connected test reference](connected-test-reference.md): look up the exact
  behavior of each connected test helper, wait, and failure.
- [Troubleshooting](troubleshooting.md): fix a page that does not connect,
  loads no assets, rejects a form with a CSRF error, or keeps reconnecting.

### Operations {#operations}

- [Lifecycle observability](lifecycle-observability.md): export LiveView
  metrics to Prometheus, log lifecycle events without leaking application
  data, and read Scalive's own log levels and annotations.
- [Deployment](deployment.md): package the application, put it behind HTTPS
  and a reverse proxy, and roll out new versions.
