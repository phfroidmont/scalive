{%
title = "Where to go next"
description = "Choose the Scalive guide, example, or API reference that matches the next part of your application."
order = 7
section = learn
%}

## Continue By Task {#continue-by-task}

The Learn path established the programming model and architecture. Continue by
the task your application needs:

- **Set up the browser:** [client setup and static assets](../guides/static-assets-and-client-setup.md).
- **Build interfaces and input:** [HTML and event bindings](../guides/html-dsl-and-event-bindings.md),
  [typed forms and validation](../guides/typed-forms-and-validation.md),
  [ordinary HTTP forms and redirects](../guides/http-forms-and-redirects.md), and
  [file uploads](../guides/uploads-and-consumption.md).
- **Structure and protect pages:** [routes and navigation](../guides/routes-and-navigation.md),
  [layouts and live sessions](../guides/layouts-sessions-and-mount-aspects.md),
  [services and dependency injection](../guides/services-and-zlayer-injection.md),
  and [authentication and sessions](../guides/authentication.md).
- **Own local and collection state:** [stateful components](../guides/components-and-communication.md),
  [nested LiveViews](../guides/nested-liveviews.md), and
  [streams](../guides/streams-and-collection-updates.md).
- **Manage lifecycle behavior:** [asynchronous work, subscriptions, and connected resources](../guides/async-work-and-subscriptions.md),
  [lifecycle hooks](../guides/lifecycle-hooks.md), and
  [lifecycle feedback and page state](../guides/flash-title-and-lifecycle-ux.md).
- **Integrate browser APIs:** [browser commands, events, and hooks](../guides/browser-integration.md).
- **Verify and operate:** [testing](../guides/testing.md),
  [troubleshooting](../guides/troubleshooting.md),
  [configuration](../guides/configuration.md),
  [lifecycle observability](../guides/lifecycle-observability.md), and
  [deployment](../guides/deployment.md).

The [Guides index](../guides/index.md#solve-a-task) provides the same complete
task map with a summary of each guide.

## Explore Working Examples {#explore-working-examples}

Start with the [typed counter](../examples/counter.md) and
[shopping cart](../examples/shopping-cart.md) for the model-message-render loop.
Then choose a focused executable slice:

- [Lifecycle and connection phases](../examples/lifecycle.md) for disconnected
  render, connected mount, and reconnect behavior.
- [Typed documentation navigation](../examples/navigation.md) for checked route
  destinations and browser history.
- [Validated profile form](../examples/profile-form.md) and
  [bounded text upload](../examples/text-upload.md) for typed input, field
  errors, and upload ownership.
- [Service injection](../examples/service-injection.md),
  [connected lifecycle registration](../examples/connected-resource.md),
  [async report](../examples/async-report.md), and
  [subscription clock](../examples/subscription-clock.md) for finite work and
  lifecycle-owned live data backed by application services.
- [Activity stream](../examples/activity-stream.md),
  [voting components](../examples/voting-components.md), and
  [browser integration](../examples/browser-integration.md) for larger changing
  collections, local component state, and browser-only APIs.

Examples demonstrate one behavior at a time. They are evidence for an API, not
a required application architecture.

## Look Up The Current API {#look-up-the-current-api}

Use the [API reference](../api/index.md#packages) for exact current signatures.
Scalive is alpha software, so prefer the API matching the revision or artifact
you use rather than code copied from an older discussion.

## Evaluate Scalive For An Application {#evaluate-scalive-for-an-application}

Read [Project status](../project/index.md#project-status) and
[compatibility scope](../project/index.md#compatibility-scope) before depending
on a Phoenix feature or deploying a production application. Scalive targets
useful behavioral parity, but does not imply that every Phoenix API or
operational characteristic is present.

Developers coming from Phoenix can use the
[Phoenix LiveView orientation](../guides/phoenix-live-view-orientation.md) to map
familiar concepts to Scalive's Scala-first API.
