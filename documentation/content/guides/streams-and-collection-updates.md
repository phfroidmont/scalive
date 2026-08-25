{%
title = "Streams and collection updates"
description = "Render large or frequently changing collections with stable stream identities while retaining durable domain state separately."
order = 32
section = guides
group = "State, services, and components"
%}

## Prerequisites {#prerequisites}

Read
[HTML and event bindings](html-dsl-and-event-bindings.md#key-repeated-content)
before choosing a stream over ordinary keyed rendering. The entries rendered by
this guide must have stable domain keys.

## Choose Streams Deliberately {#choose-streams-deliberately}

Ordinary Scala collections are the default. Render a collection normally when
the complete value belongs in the model and ordinary tree diffing is sufficient.
Use @:apiSymbol(extension:scalive.splitBy)`splitBy`@:@ to give repeated entries stable keys.
Keyed rendering sends sparse entry updates and positional references, then lets
the client patch the resulting HTML normally.

Use a stream when collection changes should instead become explicit
ID-addressed insert, update, delete, or reset operations in the browser. Streams
are especially useful for feeds, logs, and bounded windows that change
frequently. They require more explicit ownership than keyed rendering, so they
are not a general replacement for `Vector`, database state, or another durable
source of truth.

## Separate Domain State From Stream State {#separate-domain-state-from-stream-state}

A @:apiSymbol(type-alias:scalive.LiveStream)`LiveStream[A]`@:@ is an opaque, immutable
rendering handle. It intentionally does not expose collection operations or its
entries. Keep queryable domain data separately and store the latest stream
handle beside it:

```scala
final case class Model(
  activities: Vector[Activity],
  activityStream: LiveStream[Activity],
  nextId: Int
)
```

The `Vector` can answer application questions such as total activity count and
category totals. The stream handle carries the current snapshot and pending DOM
operations. Stream state belongs to one socket or component lifecycle, so mount
must recreate it after a remount.

This separation also permits a bounded DOM without discarding domain data. The
activity example keeps its complete history in a `Vector` while retaining only
five rendered rows.

## Define Stable Identity And Retention {#define-stable-identity-and-retention}

Create one @:apiSymbol(type-alias:scalive.LiveStreamDef)`LiveStreamDef[A, Id]`@:@ for each
logical stream. Its name identifies the stream within the owning LiveView or
component, and its DOM-ID function identifies rows:

```scala
private val ActivityStreamDef =
  LiveStreamDef.byId[Activity, Int]("activity")(_.id).keepLast(5)
```

Every generated ID must be stable, non-empty, and unique in the rendered
document. Inserting an item with an existing ID updates that row in place.
Changing an item's ID does not remove the row with its old ID; delete the old
identity explicitly when a domain operation changes identity.

`keepFirst(count)` and `keepLast(count)` apply a retention policy during create,
reset, and insertion operations. Counts must be positive. Retention limits the
stream snapshot and rendered DOM, not separately retained domain state.

## Create The Stream During Mount {#create-the-stream-during-mount}

Call @:apiSymbol(def:scalive.Streams.create)`ctx.streams.create`@:@ once for a stream
name in each lifecycle and retain the returned handle:

```scala
def mount(ctx: MountContext): Task[Model] =
  ctx.streams.create(ActivityStreamDef, InitialActivities).map { stream =>
    Model(InitialActivities, stream, nextId = 5)
  }
```

Creating the same name twice for one owner fails. Definitions are owner-scoped,
but rendered container and row IDs are still document IDs and must remain unique
across nested LiveViews and component instances.

## Retain Every Replacement Handle {#retain-every-replacement-handle}

Every stream operation returns a replacement handle. Store and render that exact
value; rendering an older handle loses the pending operation:

```scala
case Msg.Add =>
  val activity = Activity(model.nextId, "Streams", "Inserted one row")
  ctx.streams.insert(ActivityStreamDef, activity).map { stream =>
    model.copy(
      activities = model.activities :+ activity,
      activityStream = stream,
      nextId = model.nextId + 1
    )
  }
```

@:apiSymbol(def:scalive.Streams.insert)`insert`@:@ appends by default. Supply a
@:apiSymbol(type-alias:scalive.StreamAt)`StreamAt`@:@ value to insert first or at an
index, and use `updateOnly = true` to ignore a missing identity instead of
inserting it. Bulk insertion behaves like repeated insertion at the same
position, so repeatedly inserting first or at one fixed index can reverse input
order.

## Delete And Reset Coherently {#delete-and-reset-coherently}

Apply the same domain operation to durable data and the stream. The definition
retains the domain ID type and maps that ID to the row's DOM ID, so deletion does
not need the complete item:

```scala
case Msg.Delete(activityId) =>
  ctx.streams.delete(ActivityStreamDef, activityId).map { stream =>
    model.copy(
      activities = model.activities.filterNot(_.id == activityId),
      activityStream = stream
    )
  }
```

@:apiSymbol(def:scalive.Streams.deleteByDomId)`deleteByDomId`@:@ is a lower-level
alternative for a trusted rendered DOM ID belonging to that stream. Prefer
`delete` with a typed domain ID, and do not pass untrusted browser input to the
DOM-ID operation.

@:apiSymbol(def:scalive.Streams.reset)`reset`@:@ replaces the stream snapshot and
instructs the browser to rebuild the container. Reset durable state in the same
message handler when the user-facing contract resets the whole example:

```scala
case Msg.Reset =>
  ctx.streams.reset(ActivityStreamDef, InitialActivities).map { stream =>
    Model(InitialActivities, stream, nextId = 5)
  }
```

## Render The Stream Container {#render-the-stream-container}

@:apiSymbol(extension:scalive.renderIn)`renderIn`@:@ creates the stream container,
assigns its required update mode, and assigns each projected row its generated
DOM ID:

```scala
model.activityStream.renderIn(ol, aria.label := "Recent activity") { activity =>
  li(
    p(activity.summary),
    button(on.click(Msg.Delete(activity.id)), "Delete")
  )
}
```

Do not override the container or row IDs produced by the stream. Keep controls
inside each projected row typed to the owning LiveView's message type as usual.

The complete implementation is extracted from executable source:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ActivityStreamExample.scala, activity-stream-example)

Try insertion, bounded retention, deletion, and reset in the
[bounded activity stream example](../examples/activity-stream.md).

## Related Tasks {#related-tasks}

- Keep simpler repeated content keyed with [HTML and event bindings](html-dsl-and-event-bindings.md#key-repeated-content).
- Feed repeated updates from lifecycle-owned work with [Asynchronous work and subscriptions](async-work-and-subscriptions.md#prerequisites).
- Exercise initial collection rendering with [Testing LiveViews](testing.md#test-disconnected-rendering).
