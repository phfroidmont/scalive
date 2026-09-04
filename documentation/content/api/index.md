{%
title = "API"
description = "Generated reference for Scalive's supported public packages and import surface."
order = 0
section = api
%}

Browse Scalive's supported public API by package or use **Filter symbols** in the
API browser. Symbols inherited or exported through `import scalive.*` appear
under that public import path.

## Packages {#packages}

- `scalive` contains the core LiveView, component, HTML, form,
  routing, upload, and runtime APIs.
- `scalive.codecs` contains codecs used at browser and
  route boundaries.
- `scalive.testing` contains disconnected rendering,
  page queries, and typed form test utilities.

## Find a symbol {#find-a-symbol}

Use the API browser to explore packages and nested owners. Global documentation
search (`Ctrl K`) searches API declarations and members alongside guides and
examples.

## Core abstraction {#core-abstraction}

Start with @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@, the typed boundary for mounting
state, handling messages, and rendering HTML.

## Forms {#forms}

Start structured browser input with
@:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@ and combine fields into a
@:apiSymbol(class:scalive.FormDefinition)`FormDefinition`@:@. The definition
owns its @:apiSymbol(class:scalive.Form)`Form`@:@ values and typed
@:apiSymbol(class:scalive.FormEvent)`FormEvent`@:@ messages. See the
[typed forms guide](../guides/typed-forms-and-validation.md#define-a-rooted-form) for the
complete path, then use the [profile](../examples/profile-form.md),
[repeated rows](../examples/repeated-contacts-form.md), and
[save workflow](../examples/form-save-workflow.md) examples in order.

Advanced entry points include
@:apiSymbol(class:scalive.PhoenixNestedParamsAdapter)`PhoenixNestedParamsAdapter`@:@
for indexed compatibility payloads,
@:apiSymbol(class:scalive.FormWorkflow)`FormWorkflow`@:@ for coordinated
persistence, and @:apiSymbol(object:scalive.HttpFormDecoder)`HttpFormDecoder`@:@
for bounded CSRF-validated POST bodies.

Lifecycle capabilities include
@:apiSymbol(trait:scalive.ConnectedResources)`ConnectedResources`@:@ for
non-message acquisition and finalization during connected mount. The
[lifecycle-owned work guide](../guides/async-work-and-subscriptions.md#choose-the-resource-by-shape)
compares it with managed async tasks and subscriptions.

Route-wide operational capabilities include
@:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ and the
@:apiSymbol(object:scalive.LifecycleMetrics)`LifecycleMetrics`@:@ adapter. The
[lifecycle observability guide](../guides/lifecycle-observability.md#choose-the-observation-boundary)
defines their coverage and fixed metric contract.
