# Opaque LiveStream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `LiveStream[A]` an opaque render-only handle so application code cannot confuse runtime stream commands or rendering snapshots with durable business state.

**Architecture:** Replace the public `LiveStream` case class with a final class whose constructor and fields are package-private, while preserving the existing internal stream runtime and rendering data flow. Move fixture lookups to an application-owned parent map, then remove stale public API documentation and the resolved assessment finding.

**Tech Stack:** Scala 3.7.3, ZIO 2, ZIO Test, Mill, Phoenix LiveView upstream Playwright fixtures.

## Global Constraints

- `LiveStream[A]` remains the return type of `Streams.init`, `insert`, `delete`, and `deleteByDomId`.
- The existing `stream.stream { (domId, item) => ... }` rendering syntax remains unchanged.
- `LiveStream` exposes no public fields, extractor, collection operations, `isEmpty`, or `nonEmpty`.
- `LiveStreamEntry` is package-private and is not exported from `scalive.*`.
- Pending commands and the rendering snapshot remain internal; wire behavior and snapshot algorithms do not change.
- Queryable business state belongs in the application model.
- Add no compatibility aliases.
- Remove the resolved finding from `doc/user-facing-api-assessment.md`; do not relabel it as addressed.
- Do not create commits unless the user explicitly requests them.

---

### Task 1: Enforce The Opaque Stream Boundary

**Files:**
- Create: `scalive/test/src/scaliveapi/StreamOpacitySpec.scala`
- Modify: `scalive/src/scalive/streams/LiveStream.scala:43-63`
- Modify: `scalive/src/scalive/socket/SocketStreamRuntime.scala:280-313, 412-413`

**Interfaces:**
- Consumes: existing `LiveStreamDef[A]`, `LiveStreamInsert`, `SocketStreamRuntime.toLiveStream`, and `LiveStream.stream` rendering extension.
- Produces: `final class LiveStream[+A] private[scalive] (...)` with package-private runtime fields and `private[scalive] def withName(name: String): LiveStream[A]`.

- [ ] **Step 1: Add a compile-time regression test for the public boundary**

Create a test in package `scaliveapi`, outside the `scalive` package-private boundary:

```scala
package scaliveapi

import zio.test.*

object StreamOpacitySpec extends ZIOSpecDefault:
  override def spec = suite("StreamOpacitySpec")(
    test("LiveStream exposes no runtime state to application code") {
      val nameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def streamName(stream: LiveStream[Int]) = stream.name
      """)
      val entriesErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def entries(stream: LiveStream[Int]) = stream.entries
      """)
      val emptyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def empty(stream: LiveStream[Int]) = stream.isEmpty
      """)
      val nonEmptyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def nonEmpty(stream: LiveStream[Int]) = stream.nonEmpty
      """)
      val entryTypeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def entry(value: Int): LiveStreamEntry[Int] = LiveStreamEntry("item-1", value)
      """)
      val extractorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def inspect(stream: LiveStream[Int]) = stream match
          case LiveStream(_, _, _, _, _, _, _) => ()
      """)

      assertTrue(
        nameErrors.nonEmpty,
        entriesErrors.nonEmpty,
        emptyErrors.nonEmpty,
        nonEmptyErrors.nonEmpty,
        entryTypeErrors.nonEmpty,
        extractorErrors.nonEmpty
      )
    }
  )
end StreamOpacitySpec
```

- [ ] **Step 2: Run the regression test and verify it fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.StreamOpacitySpec
```

Expected: `StreamOpacitySpec` fails because the current public fields, methods, exported entry type, and case-class extractor compile successfully, leaving one or more error collections empty.

- [ ] **Step 3: Replace the public case class with an opaque final class**

Replace the stream entry and stream declarations and update the exports in `LiveStream.scala`:

```scala
final private[scalive] case class LiveStreamEntry[+A](domId: String, value: A)

final private[scalive] case class LiveStreamInsert(
  domId: String,
  at: Int,
  limit: Option[Int],
  updateOnly: Option[Boolean])

final class LiveStream[+A] private[scalive] (
  private[scalive] val name: String,
  private[scalive] val entries: Vector[LiveStreamEntry[A]],
  private[scalive] val snapshotEntries: Vector[LiveStreamEntry[A]],
  private[scalive] val ref: String,
  private[scalive] val inserts: Vector[LiveStreamInsert],
  private[scalive] val deleteIds: Vector[String],
  private[scalive] val reset: Boolean):
  private[scalive] def withName(name: String): LiveStream[A] =
    new LiveStream(name, entries, snapshotEntries, ref, inserts, deleteIds, reset)

object api:
  export _root_.scalive.streams.{LiveStream, LiveStreamDef, StreamAt, StreamLimit}
```

This intentionally removes `isEmpty`, `nonEmpty`, case-class extraction, and the `LiveStreamEntry` export.

- [ ] **Step 4: Adapt internal construction and scoped renaming**

In `SocketStreamRuntime.toLiveStream`, construct the class explicitly:

```scala
yield new LiveStream(
  name = stream.name,
  entries = decodedEntries,
  snapshotEntries = decodedAllEntries,
  ref = stream.ref,
  inserts = dedupedInserts
    .map(insert =>
      _root_.scalive.streams.LiveStreamInsert(
        domId = insert.domId,
        at = insert.at,
        limit = insert.limit,
        updateOnly = insert.updateOnly
      )
    ).toVector,
  deleteIds = stream.deleteIds.toVector,
  reset = stream.reset
)
```

In `ScopedStreamRuntime.unscoped`, replace case-class copying with the internal method:

```scala
private def unscoped[A](stream: LiveStream[A], definition: LiveStreamDef[A]): LiveStream[A] =
  stream.withName(definition.name)
```

- [ ] **Step 5: Run focused stream tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.StreamOpacitySpec
mill --ticker false scalive.test.testOnly scalive.StreamApiSpec
```

Expected: `StreamOpacitySpec` and all existing `StreamApiSpec` wire payload tests pass.

- [ ] **Step 6: Check the task diff**

Run:

```bash
git diff --check -- scalive/src/scalive/streams/LiveStream.scala scalive/src/scalive/socket/SocketStreamRuntime.scala scalive/test/src/scaliveapi/StreamOpacitySpec.scala
```

Expected: no output and exit status 0.

---

### Task 2: Move Fixture Lookups Into Application State

**Files:**
- Modify: `e2eApp/src/StreamLiveView.scala:840-969`

**Interfaces:**
- Consumes: the opaque `LiveStream[ParentItem]` from Task 1 and existing `Streams` operations.
- Produces: `Model(items: LiveStream[ParentItem], parentsById: Map[String, ParentItem])`, with all parent lookup and update logic using `parentsById`.

- [ ] **Step 1: Compile the E2E application and verify the old state lookup fails**

Run:

```bash
mill --ticker false e2eApp.compile
```

Expected: compilation fails at the two `model.items.entries` accesses in `StreamNestedComponentResetLiveView`, proving application code can no longer inspect stream runtime state.

- [ ] **Step 2: Store initial parent items in the application model**

Change `mount` to retain the parent list independently of the stream:

```scala
def mount(ctx: MountContext) =
  for
    a       <- buildParentItem("a", "A", ctx.streams)
    b       <- buildParentItem("b", "B", ctx.streams)
    c       <- buildParentItem("c", "C", ctx.streams)
    d       <- buildParentItem("d", "D", ctx.streams)
    parents = List(a, b, c, d)
    items   <- ctx.streams.init(ItemsStreamDef, parents)
  yield Model(
    items = items,
    parentsById = parents.iterator.map(parent => parent.id -> parent).toMap
  )
```

Change the model declaration to:

```scala
final case class Model(
  items: LiveStream[ParentItem],
  parentsById: Map[String, ParentItem])
```

- [ ] **Step 3: Read and update parent state through `parentsById`**

Replace `reorderNested` with application-owned lookup and update behavior:

```scala
private def reorderNested(
  model: Model,
  id: String,
  streams: Streams
): LiveIO[Model] =
  if id.isEmpty then model
  else
    for
      nested <- streams.init(
                  nestedStreamDef(id),
                  reorderedNestedItems,
                  reset = true
                )
      current <- model.parentsById.get(id) match
                   case Some(value) => ZIO.succeed(value)
                   case None        => buildParentItem(id, id.toUpperCase, streams)
      updatedParent = current.copy(nested = nested)
      items <- streams.insert(
                 ItemsStreamDef,
                 updatedParent,
                 updateOnly = true
               )
    yield model.copy(
      items = items,
      parentsById = model.parentsById.updated(id, updatedParent)
    )
```

Replace `reorderParents` with:

```scala
private def reorderParents(model: Model, streams: Streams): LiveIO[Model] =
  for
    parentA <- model.parentsById.get("a") match
                 case Some(value) => ZIO.succeed(value)
                 case None        => buildParentItem("a", "A", streams)
    parentE  <- buildParentItem("e", "E", streams)
    parentF  <- buildParentItem("f", "F", streams)
    parentG  <- buildParentItem("g", "G", streams)
    parents  = List(parentE, parentA, parentF, parentG)
    items    <- streams.init(ItemsStreamDef, parents, reset = true)
  yield model.copy(
    items = items,
    parentsById = parents.iterator.map(parent => parent.id -> parent).toMap
  )
```

- [ ] **Step 4: Compile the E2E application**

Run:

```bash
mill --ticker false e2eApp.compile
```

Expected: compilation succeeds with no access to `LiveStream` internals.

- [ ] **Step 5: Run upstream browser parity scenarios**

Run:

```bash
./scripts/e2e-run-upstream.sh
```

Expected: the upstream Playwright suite passes, including nested stream reset and parent reorder behavior.

- [ ] **Step 6: Check the task diff**

Run:

```bash
git diff --check -- e2eApp/src/StreamLiveView.scala
```

Expected: no output and exit status 0.

---

### Task 3: Reconcile Public Documentation And Verify The Project

**Files:**
- Modify: `doc/public-api-reference.md:1201-1227`
- Modify: `doc/user-facing-api-assessment.md:46-50, 99-107`
- Modify: `doc/api-improvement-ideas.md:57-70`

**Interfaces:**
- Consumes: the opaque public API and application-state pattern delivered by Tasks 1 and 2.
- Produces: documentation that presents `LiveStream[A]` only as a render handle and contains no unresolved finding or improvement note for the removed public state.

- [ ] **Step 1: Update the public API reference**

Replace the `LiveStreamEntry` and case-class snippets in the Streams API section with:

````markdown
`LiveStream[A]` is an opaque rendering handle returned by the `Streams` facade. Render it with the `.stream` extension:

```scala
items.stream { (domId, item) =>
  li(idAttr := domId, item.toString)
}
```

`LiveStream` does not expose its entries or pending commands. Keep queryable, durable items in the application model rather than treating stream runtime state as business state.
````

Keep the surrounding `LiveStreamDef`, `StreamAt`, `StreamLimit`, and export documentation intact.

- [ ] **Step 2: Remove the resolved assessment finding and stale inventory claim**

In `doc/user-facing-api-assessment.md`:

- Change the Streams inventory bullet so it lists only `LiveStreamDef`, `LiveStream`, `StreamAt`, and `StreamLimit` as public stream types.
- Remove the complete section from `### Medium - API Design - Stream public state mixes durable state and pending commands` through `Confidence: High.`
- Leave the next finding, `Repeated string identifiers make invalid states easy`, as the immediate successor.

- [ ] **Step 3: Remove the resolved improvement note**

Delete the complete `### Clarify public stream state versus stream commands` section from `doc/api-improvement-ideas.md`, including its current issue and ideas lists. The behavior is now represented by the implementation and public API reference rather than a backlog item.

- [ ] **Step 4: Verify stale public API descriptions are gone**

Run:

```bash
rg -n 'LiveStreamEntry|LiveStream\.entries|items\.entries|Stream public state mixes durable state and pending commands|Clarify public stream state versus stream commands' doc/public-api-reference.md doc/user-facing-api-assessment.md doc/api-improvement-ideas.md e2eApp/src/StreamLiveView.scala
```

Expected: no matches.

- [ ] **Step 5: Format and fix the project**

Run:

```bash
mill --ticker false __.reformat + __.fix
```

Expected: all formatter and Scalafix tasks complete successfully. Review any generated changes and retain only changes belonging to this feature.

- [ ] **Step 6: Run the full native test suite**

Run:

```bash
mill --ticker false __.test
```

Expected: all native test suites pass.

- [ ] **Step 7: Recompile the E2E application after formatting**

Run:

```bash
mill --ticker false e2eApp.compile
```

Expected: compilation succeeds.

- [ ] **Step 8: Inspect the final diff**

Run:

```bash
git diff --check
git status --short
git diff -- scalive/src/scalive/streams/LiveStream.scala scalive/src/scalive/socket/SocketStreamRuntime.scala scalive/test/src/scaliveapi/StreamOpacitySpec.scala e2eApp/src/StreamLiveView.scala doc/public-api-reference.md doc/user-facing-api-assessment.md doc/api-improvement-ideas.md doc/superpowers/specs/2026-07-17-opaque-live-stream-design.md doc/superpowers/plans/2026-07-17-opaque-live-stream.md
```

Expected: `git diff --check` has no output; status and diff show only the intended opaque stream API, fixture state migration, tests, design, plan, and documentation changes.
