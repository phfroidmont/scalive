# Opaque LiveStream Design

## Goal

Separate the public stream rendering API from the runtime state used to build stream diffs. Application code must keep durable, queryable business state in its own model rather than reading pending commands or rendering snapshots from `LiveStream`.

## Public API

`LiveStream[A]` becomes an opaque render handle. It remains the value returned by `ctx.streams.init`, `insert`, `delete`, and `deleteByDomId`, and remains the receiver of the existing `.stream` rendering extension.

The class exposes no public fields, extractor, collection operations, or snapshot-derived status methods. In particular, the following API is removed:

- `LiveStream.entries`
- `LiveStream.isEmpty`
- `LiveStream.nonEmpty`
- the public `LiveStreamEntry` type and its `scalive.*` export

No compatibility aliases are added. Scalive is alpha, and preserving access to runtime state would retain the incorrect application-state model this change is intended to remove.

## Internal Representation

`LiveStream[A]` is a final class whose constructor and runtime fields are visible only within `scalive`. Its internal data continues to include:

- the stream name and reference
- pending inserts, deletes, and reset state for the next wire diff
- the current keyed rendering snapshot

`LiveStreamEntry` becomes package-private. `SocketStreamRuntime` constructs handles directly, and `CollectionOps.stream` reads their package-private state to render keyed content and attach stream commands.

Pruning continues to clear pending wire commands while preserving the rendering snapshot. Insert, delete, reset, limit, and component-scoping behavior do not change.

## Application State

Application decisions must use application-owned model fields rather than `LiveStream` internals. A model may hold both a durable collection and a corresponding `LiveStream` handle when it needs to query items as well as render stream updates.

The nested-component-reset E2E fixture currently looks up parent items through `model.items.entries`. Its model will instead retain an application-owned parent collection alongside the stream handle, update both when parent items change, and use the collection for lookups.

## Upstream Alignment

Phoenix LiveView `v1.1.28` does not expose durable stream contents. Its `LiveStream` contains pending commands and permits consumption only during a render comprehension; attempts to inspect it as application state fail with guidance to retain the relevant information separately.

Scalive will follow that user-facing model while retaining its private rendering snapshot as an implementation detail required by its tree-diff architecture.

## Testing

Tests will verify:

- external application code cannot access stream entries, status methods, or `LiveStreamEntry`
- stream initialization and insertion still encode the expected inserts
- pruning clears pending inserts without losing the rendering snapshot
- deletion, reset, limits, and rendering payload behavior remain unchanged
- the nested-component-reset fixture uses application-owned state and continues to pass its upstream E2E scenarios

Package-level runtime tests may inspect package-private state when necessary to assert wire-command behavior. Such assertions do not make that state part of the user-facing API; future dedicated test helpers can replace them independently.

## Documentation

The public API reference will describe `LiveStream[A]` as an opaque render handle and state that queryable items belong in the application model. It will remove `LiveStreamEntry`, `entries`, `isEmpty`, and `nonEmpty` from the documented API.

Because the API assessment is a current-state audit, the resolved "Stream public state mixes durable state and pending commands" finding will be removed rather than relabeled as addressed. Related improvement notes may be updated to reflect the completed change without preserving stale API descriptions.

## Scope

This change does not redesign the `Streams` facade, stream definitions, placement or limit types, rendering syntax, wire protocol, runtime snapshot algorithm, or future testing APIs.
