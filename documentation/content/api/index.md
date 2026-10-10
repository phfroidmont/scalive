{%
title = "API"
description = "Generated reference for Scalive's supported public packages and import surface."
order = 0
section = api
%}

Browse Scalive's public API by package, or type in **Filter packages and types**
above the package list. On a type's page, **Find a member** filters its members.
Symbols inherited or exported through `import scalive.*` appear under that
import path. Global documentation search (`Ctrl K`) also finds API declarations
and members.

## Packages {#packages}

- @:apiSymbol(package:scalive)`scalive`@:@ contains everything an application
  imports with `import scalive.*`: LiveViews, components, HTML, forms, routing,
  uploads, and the ZIO HTTP server integration.
- @:apiSymbol(package:scalive.codecs)`scalive.codecs`@:@ contains the codecs
  that encode attribute values and route parameters.
- @:apiSymbol(package:scalive.testing)`scalive.testing`@:@ contains the
  connected test client
  (@:apiSymbol(object:scalive.testing.ConnectedRender)`ConnectedRender`@:@ and
  @:apiSymbol(class:scalive.testing.ConnectedView)`ConnectedView`@:@),
  disconnected rendering
  (@:apiSymbol(object:scalive.testing.DisconnectedRender)`DisconnectedRender`@:@),
  page queries, and typed form test utilities.

## Find the core types {#core-types}

A first application uses these types. Their reference pages link to the guides
that use them.

| Task | Start with |
| --- | --- |
| Define a page | @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@, or @:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ when it reads URL parameters |
| Use lifecycle capabilities | @:apiSymbol(trait:scalive.MountContext)`MountContext`@:@, @:apiSymbol(trait:scalive.MessageContext)`MessageContext`@:@, and @:apiSymbol(enum:scalive.Connection)`Connection`@:@ |
| Render from the model | @:apiSymbol(trait:scalive.Signal)`Signal`@:@ |
| Bind browser events | @:apiSymbol(object:scalive.on)`on`@:@ and @:apiSymbol(class:scalive.HtmlAttrBinding)`HtmlAttrBinding`@:@ |
| Declare routes | @:apiSymbol(val:scalive.live)`live`@:@, @:apiSymbol(class:scalive.LiveRouteBuilder)`LiveRouteBuilder`@:@, and @:apiSymbol(val:scalive.Live.router)`Live.router`@:@ |
| Group routes that share mount policy | @:apiSymbol(class:scalive.LiveSessionBuilder)`LiveSessionBuilder`@:@ |
| Render the document and shared chrome | @:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ and @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ |
| Serve the application | @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@ |
| Patch the URL or navigate | @:apiSymbol(trait:scalive.Navigation)`Navigation`@:@ |
| Run background work | @:apiSymbol(trait:scalive.Async)`Async`@:@, @:apiSymbol(trait:scalive.Subscriptions)`Subscriptions`@:@, and @:apiSymbol(enum:scalive.AsyncValue)`AsyncValue`@:@ |
| Show brief feedback | @:apiSymbol(trait:scalive.Flash)`Flash`@:@ |
| Build a form | @:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@ and @:apiSymbol(class:scalive.FormDefinition)`FormDefinition`@:@ |
| Reuse a stateful part of a page | @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent`@:@ |
| Test a LiveView | @:apiSymbol(object:scalive.testing.ConnectedRender)`ConnectedRender`@:@ |

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

## Lifecycle resources and observability {#lifecycle-resources-and-observability}

@:apiSymbol(trait:scalive.ConnectedResources)`ConnectedResources`@:@ acquires
and releases resources that send no messages during a connected mount. The
[connected resources guide](../guides/connected-resources.md#prerequisites)
compares it with asynchronous work and subscriptions.

@:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ and the
@:apiSymbol(object:scalive.LifecycleMetrics)`LifecycleMetrics`@:@ adapter
observe every route's lifecycle events. The
[lifecycle observability guide](../guides/lifecycle-observability.md#choose-the-observation-boundary)
defines their coverage and fixed metric contract.
