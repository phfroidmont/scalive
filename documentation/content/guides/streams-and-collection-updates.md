{%
title = "Streams and collection updates"
description = "Render large or frequently changing collections with stable stream identities while keeping domain data separately in the model."
order = 32
section = guides
group = "State and components"
%}

## Before you start {#prerequisites}

Start with a collection that renders correctly and whose entries have stable
domain keys. If keyed rendering is not yet in place, see
[HTML and event bindings](html-dsl-and-event-bindings.md#key-repeated-content).

## Choose streams deliberately {#choose-streams-deliberately}

Ordinary Scala collections are the default. Render a collection normally when
the complete value belongs in the model and ordinary tree diffing is sufficient.
Use @:apiSymbol(extension:scalive.splitBy)`splitBy`@:@ to give repeated entries stable keys.
Keyed rendering sends sparse entry updates and positional references, then lets
the client patch the resulting HTML normally.

Use a stream when collection changes should instead become explicit
ID-addressed insert, update, delete, or reset operations in the browser. Streams
are especially useful for feeds, logs, and bounded windows that change
frequently. They require more explicit ownership than keyed rendering, so they
are not a general replacement for a `Vector` in the model, a database, or another
source of truth.

## Separate domain state from stream state {#separate-domain-state-from-stream-state}

A @:apiSymbol(type-alias:scalive.LiveStream)`LiveStream[A]`@:@ is an opaque, immutable
rendering handle. It intentionally does not expose collection operations or its
entries. Keep queryable domain data separately and store the latest stream
handle beside it:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-model)

The `Vector` can answer application questions such as the total activity count.
The stream handle carries the current snapshot and pending DOM operations.
Stream state belongs to one LiveView or component lifecycle, so mount must
recreate it after a remount.

This separation also permits a bounded DOM without discarding domain data. The
activity example keeps its complete history in a `Vector` while retaining only
five rendered rows.

## Define stable identity and retention {#define-stable-identity-and-retention}

Create one @:apiSymbol(type-alias:scalive.LiveStreamDef)`LiveStreamDef[A, Id]`@:@ for each
logical stream. Its name identifies the stream within the owning LiveView or
component, and its DOM-ID function identifies rows:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-definition)

Every generated ID must be stable, non-empty, and unique in the rendered
document. Inserting an item with an existing ID updates that row in place.
Changing an item's ID does not remove the row with its old ID; delete the old
identity explicitly when a domain operation changes identity.

`keepFirst(count)` and `keepLast(count)` apply a retention policy during
connected creation, reset, and insertion operations. Counts must be positive.
Disconnected initial rendering keeps the complete initial stream, so the
initial HTML remains useful when JavaScript never connects; the connected DOM
is pruned to the configured limit. Retention limits stream state, not separately
retained domain state.

## Create the stream during mount {#create-the-stream-during-mount}

Call @:apiSymbol(def:scalive.Streams.create)`ctx.streams.create`@:@ once for a stream
name in each lifecycle and retain the returned handle:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-mount)

Creating the same name twice for one owner fails. Definitions are owner-scoped,
but rendered container and row IDs are still document IDs and must remain unique
across nested LiveViews and component instances.

## Retain every replacement handle {#retain-every-replacement-handle}

Every stream operation returns a replacement handle. Store and render that exact
value; rendering an older handle loses the pending operation:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-insert)

@:apiSymbol(def:scalive.Streams.insert)`insert`@:@ appends by default. Supply a
@:apiSymbol(type-alias:scalive.StreamAt)`StreamAt`@:@ value to insert first or at an
index, and use `updateOnly = true` to ignore a missing identity instead of
inserting it. Bulk insertion behaves like repeated insertion at the same
position, so repeatedly inserting first or at one fixed index can reverse input
order.

## Delete and reset coherently {#delete-and-reset-coherently}

Apply the same domain operation to the domain data and the stream. The definition
retains the domain ID type and maps that ID to the row's DOM ID, so deletion does
not need the complete item:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-delete)

@:apiSymbol(def:scalive.Streams.deleteByDomId)`deleteByDomId`@:@ is a lower-level
alternative for a trusted rendered DOM ID belonging to that stream. Prefer
`delete` with a typed domain ID, and do not pass untrusted browser input to the
DOM-ID operation.

@:apiSymbol(def:scalive.Streams.reset)`reset`@:@ replaces the stream snapshot and
instructs the browser to rebuild the container. Reset the domain data in the same
message handler when the user-facing contract resets the whole list:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-reset)

## Render the stream container {#render-the-stream-container}

Inside `view`, `model` is a `Signal[Model]`, so select the stream handle with
`model.map(_.activityStream)`. @:apiSymbol(extension:scalive.renderIn)`renderIn`@:@
creates the stream container, assigns its required update mode, and assigns each
projected row its generated DOM ID:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-render)

Each row receives a `Signal[Activity]`. Read its fields with `map`, and build a
row-specific message the same way:
`on.click(activity.map(value => Msg.Delete(value.id)))` sends the ID of the row
that was clicked. Do not override the container or row IDs produced by the
stream.

The complete recipe on this page:

@:sourceRegion(documentation/fixtures/guides/src/activity/ActivityLiveView.scala, streams-liveview)

Try insertion, bounded retention, deletion, and reset in the
[bounded activity stream example](../examples/activity-stream.md).

## Related tasks {#related-tasks}

- Keep simpler repeated content keyed with [HTML and event bindings](html-dsl-and-event-bindings.md#key-repeated-content).
- Feed repeated updates from lifecycle-owned work with [Asynchronous work](async-work.md#prerequisites).
- Exercise initial collection rendering with [Testing LiveViews](testing.md#test-disconnected-rendering).
