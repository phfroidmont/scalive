# High-Priority API Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first high-priority API polish slice safe, externally usable, and discoverable without introducing typed outbound routes.

**Architecture:** Keep changes local to the existing public API types. Add non-throwing helper behavior in `HtmlAttrBinding`, expose a minimal public upload writer state API, and refresh only the docs that currently mislead users about these APIs.

**Tech Stack:** Scala 3, ZIO, ZIO Test, Mill, Markdown documentation.

## Global Constraints

- Use the existing repository convention `doc/superpowers/...`, not `docs/superpowers/...`.
- Do not implement typed outbound route/location APIs in this plan.
- Do not add broad string identifier wrappers.
- Do not rename stream public state in this plan.
- Keep changes minimal and local; avoid speculative abstractions.
- Follow TDD for code changes: failing test first, minimal implementation second.
- Run verification with `mill --ticker false scalive.test` after code tasks and `mill --ticker false __.test` if time allows after all changes.
- Do not create git commits unless the user explicitly requests commits.

---

## File Structure

- Modify `scalive/src/scalive/HtmlElement.scala`: add optional event value helpers and make existing helpers non-throwing.
- Modify `scalive/test/src/scalive/HtmlMessageTypeSafetySpec.scala`: add behavior tests for missing and invalid event payload values while preserving type-safety tests.
- Modify `scalive/src/scalive/upload/LiveUpload.scala`: make `LiveUploadWriterState` publicly constructible and add `valueAs[A]`.
- Modify `scalive/test/src/scalive/upload/LiveUploadSpec.scala`: add an external-style writer test that uses only public writer state APIs.
- Create `README.md`: add newcomer-facing project overview, quickstart, minimal LiveView, example/test commands, and documentation links.
- Modify `doc/public-api-reference.md`: refresh binding helper, upload writer state, lifecycle context, link API, and duplicate component guidance sections touched by this slice.

---

### Task 1: Safe Attribute Value Binding Helpers

**Files:**
- Modify: `scalive/src/scalive/HtmlElement.scala:41-68`
- Modify: `scalive/test/src/scalive/HtmlMessageTypeSafetySpec.scala:5-71`

**Interfaces:**
- Consumes: `HtmlAttrBinding.apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg]`
- Produces: `HtmlAttrBinding.withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg]`
- Produces: `HtmlAttrBinding.withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg]`
- Produces: non-throwing `HtmlAttrBinding.withValue[Msg](f: String => Msg): Mod.Attr[Msg]`
- Produces: non-throwing `HtmlAttrBinding.withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg]`

- [ ] **Step 1: Write failing behavior tests**

Add these tests inside the `suite("HtmlMessageTypeSafetySpec")` in `scalive/test/src/scalive/HtmlMessageTypeSafetySpec.scala`, after `matching event bindings compile`:

```scala
    ,
    test("withValue uses an empty string when the payload has no value") {
      val attr = phx.onBlur.withValue(identity)

      val result = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => fail(s"expected binding, got $other")

      assertTrue(result == "")
    },
    test("withValueOption preserves missing and present values") {
      val attr = phx.onBlur.withValueOption(identity)

      val missing = attr match
        case Mod.Attr.Binding(_, f) => f(Map.empty)
        case other                  => fail(s"expected binding, got $other")

      val present = attr match
        case Mod.Attr.Binding(_, f) => f(Map("value" -> "hello"))
        case other                  => fail(s"expected binding, got $other")

      assertTrue(missing == None, present == Some("hello"))
    },
    test("withBoolValue decodes accepted values and defaults invalid values to false") {
      val attr = phx.onBlur.withBoolValue(identity)

      def decode(payload: Map[String, String]) =
        attr match
          case Mod.Attr.Binding(_, f) => f(payload)
          case other                  => fail(s"expected binding, got $other")

      assertTrue(
        decode(Map("value" -> "on")),
        decode(Map("value" -> "yes")),
        decode(Map("value" -> "true")),
        !decode(Map("value" -> "off")),
        !decode(Map("value" -> "no")),
        !decode(Map("value" -> "false")),
        !decode(Map("value" -> "unexpected")),
        !decode(Map.empty)
      )
    },
    test("withBoolValueOption preserves invalid and missing values") {
      val attr = phx.onBlur.withBoolValueOption(identity)

      def decode(payload: Map[String, String]) =
        attr match
          case Mod.Attr.Binding(_, f) => f(payload)
          case other                  => fail(s"expected binding, got $other")

      assertTrue(
        decode(Map("value" -> "true")) == Some(true),
        decode(Map("value" -> "false")) == Some(false),
        decode(Map("value" -> "unexpected")) == None,
        decode(Map.empty) == None
      )
    }
```

- [ ] **Step 2: Run tests and verify failure**

Run: `mill --ticker false scalive.test.testOnly scalive.HtmlMessageTypeSafetySpec`

Expected: compile failure because `withValueOption` and `withBoolValueOption` are not members of `HtmlAttrBinding`.

- [ ] **Step 3: Implement minimal helper behavior**

Replace the existing `withValue` and `withBoolValue` methods in `scalive/src/scalive/HtmlElement.scala` with:

```scala
  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg] =
    apply(m => f(m.get("value")))

  def withValue[Msg](f: String => Msg): Mod.Attr[Msg] =
    withValueOption(value => f(value.getOrElse("")))

  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg] =
    withValueOption(value =>
      f(value.flatMap {
        case "on" | "yes" | "true"  => Some(true)
        case "off" | "no" | "false" => Some(false)
        case _                       => None
      })
    )

  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg] =
    withBoolValueOption(value => f(value.getOrElse(false)))
```

- [ ] **Step 4: Run focused tests and verify pass**

Run: `mill --ticker false scalive.test.testOnly scalive.HtmlMessageTypeSafetySpec`

Expected: PASS.

- [ ] **Step 5: Review diff**

Run: `git diff -- scalive/src/scalive/HtmlElement.scala scalive/test/src/scalive/HtmlMessageTypeSafetySpec.scala`

Expected: only the binding helper implementation and focused tests changed.

---

### Task 2: Public Upload Writer State API

**Files:**
- Modify: `scalive/src/scalive/upload/LiveUpload.scala:111-120`
- Modify: `scalive/test/src/scalive/upload/LiveUploadSpec.scala:1-43`

**Interfaces:**
- Consumes: `LiveUploadWriter`, `LiveExternalUploadEntry`, `LiveUploadWriterCloseReason`
- Produces: public `LiveUploadWriterState(value: Any)` constructor
- Produces: `LiveUploadWriterState.valueAs[A]: Option[A]`

- [ ] **Step 1: Write failing external-style writer test**

Add imports to `scalive/test/src/scalive/upload/LiveUploadSpec.scala`:

```scala
import zio.*
```

Add this test after the `LiveUploadError.toJson` suite:

```scala
    ,
    suite("LiveUploadWriterState")(
      test("custom writers can construct and inspect public state") {
        final case class WriterState(chunks: Int, closed: Boolean)

        val writer = new LiveUploadWriter:
          def init(uploadName: String, entry: LiveExternalUploadEntry): Task[LiveUploadWriterState] =
            ZIO.succeed(LiveUploadWriterState(WriterState(0, closed = false)))

          def meta(state: LiveUploadWriterState): Json.Obj =
            val chunks = state.valueAs[WriterState].map(_.chunks).getOrElse(-1)
            Json.Obj("chunks" -> Json.Num(BigDecimal(chunks)))

          def writeChunk(data: Chunk[Byte], state: LiveUploadWriterState): Task[LiveUploadWriterState] =
            val next = state.valueAs[WriterState].get.copy(chunks = data.length)
            ZIO.succeed(LiveUploadWriterState(next))

          def close(
            state: LiveUploadWriterState,
            reason: LiveUploadWriterCloseReason
          ): Task[LiveUploadWriterState] =
            val next = state.valueAs[WriterState].get.copy(closed = true)
            ZIO.succeed(LiveUploadWriterState(next))

        val entry = LiveExternalUploadEntry(
          ref = "0",
          name = "avatar.png",
          relativePath = None,
          size = 3,
          contentType = "image/png",
          lastModified = None,
          clientMeta = None
        )

        for
          initial <- writer.init("avatar", entry)
          written <- writer.writeChunk(Chunk[Byte](1, 2, 3), initial)
          closed  <- writer.close(written, LiveUploadWriterCloseReason.Done)
        yield assertTrue(
          initial.valueAs[WriterState] == Some(WriterState(0, closed = false)),
          written.valueAs[WriterState] == Some(WriterState(3, closed = false)),
          closed.valueAs[WriterState] == Some(WriterState(3, closed = true)),
          writer.meta(written) == Json.Obj("chunks" -> Json.Num(BigDecimal(3)))
        )
      }
    )
```

- [ ] **Step 2: Run tests and verify failure**

Run: `mill --ticker false scalive.test.testOnly scalive.upload.LiveUploadSpec`

Expected: compile failure because `LiveUploadWriterState` cannot be constructed publicly and `valueAs` does not exist.

- [ ] **Step 3: Implement public state API**

Change `scalive/src/scalive/upload/LiveUpload.scala` from:

```scala
final case class LiveUploadWriterState private[scalive] (value: Any)
```

to:

```scala
final case class LiveUploadWriterState(value: Any):
  def valueAs[A]: Option[A] =
    value match
      case typed: A => Some(typed)
      case _        => None
```

- [ ] **Step 4: Run focused tests and address type erasure warning if needed**

Run: `mill --ticker false scalive.test.testOnly scalive.upload.LiveUploadSpec`

Expected: PASS or a Scala unchecked warning for `case typed: A`.

If the unchecked warning is treated as an error, replace `valueAs` with a `ClassTag`-based signature:

```scala
import scala.reflect.ClassTag

final case class LiveUploadWriterState(value: Any):
  def valueAs[A: ClassTag]: Option[A] =
    value match
      case typed: A => Some(typed)
      case _        => None
```

The corresponding test calls remain `state.valueAs[WriterState]`.

- [ ] **Step 5: Review diff**

Run: `git diff -- scalive/src/scalive/upload/LiveUpload.scala scalive/test/src/scalive/upload/LiveUploadSpec.scala`

Expected: only public state API and focused tests changed.

---

### Task 3: Root Newcomer README

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: existing examples in `example/src/CounterLiveView.scala`, `example/src/Example.scala`, and docs in `doc/public-api-reference.md`, `UPSTREAM_COMPATIBILITY.md`
- Produces: a root-level onboarding document with accurate compatibility caveats

- [ ] **Step 1: Create README content**

Create `README.md` with this content, adjusting only command names if the build reveals a more precise example run target:

```markdown
# Scalive

Scalive is a Scala 3 implementation of the Phoenix LiveView programming model.
It keeps the LiveView mental model while using Scala features for typed messages,
typed models, typed route params, ZIO effects, and a Scala HTML DSL.

Scalive is currently alpha software. APIs may change while the project optimizes
for the best user-facing Scala API.

## What A LiveView Looks Like

```scala
import scalive.*

import zio.*

object CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  enum Msg:
    case Increment
    case Decrement

  def mount(ctx: MountContext[Msg, Int]): Task[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext[Msg, Int]) =
    case Msg.Increment => ZIO.succeed(model + 1)
    case Msg.Decrement => ZIO.succeed(model - 1)

  def render(model: Int): HtmlElement[Msg] =
    div(
      button(phx.onClick(Msg.Decrement), "-"),
      span(s"Count: $model"),
      button(phx.onClick(Msg.Increment), "+")
    )
```

## Routing And Server Setup

Routes start from `scalive.live` and are assembled with `Live.router`.
See `example/src/Example.scala` for a complete runnable setup including static
assets, routes, socket configuration, and root layout wiring.

## Client Setup

Scalive uses a LiveView-compatible JavaScript client connection. The example app
shows the expected socket path and static asset setup. Start with
`example/src/RootLayout.scala` and `example/src/Example.scala` when wiring a new
application.

## Running The Project

```bash
mill --ticker false scalive.test
mill --ticker false __.test
```

The project runs inside `nix develop`; `mill` is available there.

## Documentation

- Public API reference: `doc/public-api-reference.md`
- API assessment and improvement tracking: `doc/user-facing-api-assessment.md`
- Phoenix LiveView compatibility notes: `UPSTREAM_COMPATIBILITY.md`
- Human-oriented examples: `example/src`
- Upstream parity fixtures: `e2eApp/src`

The parity fixtures are useful compatibility evidence, but they are not always
recommended application style. Prefer `example/src` and the public API reference
for learning the normal Scalive API.

## Compatibility

Scalive aims to match Phoenix LiveView behavior and feature set where that makes
sense for Scala. It intentionally diverges when Scala-first APIs improve type
safety, robustness, or ergonomics.

Do not assume complete Phoenix LiveView parity without checking
`UPSTREAM_COMPATIBILITY.md` and `doc/e2e-fixture-parity-gaps.md`.
```

- [ ] **Step 2: Verify Markdown references exist**

Run: `test -f README.md && test -f doc/public-api-reference.md && test -f UPSTREAM_COMPATIBILITY.md && test -d example/src && test -d e2eApp/src`

Expected: command exits successfully with no output.

- [ ] **Step 3: Review README diff**

Run: `git diff -- README.md`

Expected: README contains no claim of full Phoenix LiveView parity and points newcomers to examples and API docs.

---

### Task 4: Public API Reference Refresh

**Files:**
- Modify: `doc/public-api-reference.md`

**Interfaces:**
- Consumes: new helpers from Task 1 and upload state API from Task 2
- Produces: public docs that no longer advertise stale typed `link.patch` overloads or private upload writer state construction

- [ ] **Step 1: Update `HtmlAttrBinding` signature block**

In `doc/public-api-reference.md`, change the `HtmlAttrBinding` block to include the new helpers:

```scala
class HtmlAttrBinding(val name: String):
  def apply(message: ComponentTargetMessage): Mod.Attr[Nothing]
  def apply[Msg](cmd: JSCommand[Msg]): Mod.Attr[Msg]
  def apply[Msg](msg: Msg): Mod.Attr[Msg]
  def apply[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg]
  def form[Msg](f: FormData => Msg): Mod.Attr[Msg]
  def form[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg]
  def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg]
  def withValue[Msg](f: String => Msg): Mod.Attr[Msg]
  def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg]
  def withBoolValue[Msg](f: Boolean => Msg): Mod.Attr[Msg]
```

Immediately after the block, add:

```markdown
`withValue` is non-throwing and passes `""` when the client payload has no
`value`. `withBoolValue` is non-throwing and passes `false` for missing or
unrecognized values. Use the `Option` variants when application code must
distinguish missing or invalid values.
```

- [ ] **Step 2: Remove unsupported typed link overloads**

In the `Link API` block, replace:

```scala
link.navigate(path, mods*)
link.patch(path, mods*)
link.patch(codec, value, mods*)
link.patchReplace(path, mods*)
link.patchReplace(codec, value, mods*)
```

with:

```scala
link.navigate(path, mods*)
link.patch(path, mods*)
link.patchReplace(path, mods*)
```

- [ ] **Step 3: Update upload writer state block**

In the upload writer section, replace any stale state signature with:

```scala
final case class LiveUploadWriterState(value: Any):
  def valueAs[A]: Option[A]
```

If the implementation used `ClassTag`, document the precise implemented signature instead:

```scala
final case class LiveUploadWriterState(value: Any):
  def valueAs[A: ClassTag]: Option[A]
```

Add this note below the signature:

```markdown
Custom upload writers can store their own state value in `LiveUploadWriterState`.
Use `valueAs[A]` to recover the expected state type in `meta`, `writeChunk`, and
`close`.
```

- [ ] **Step 4: Ensure lifecycle context docs include `connectParams`**

Find the initial `LifecycleContext` or context availability summary and ensure it documents:

```scala
trait LifecycleContext:
  def connected: Boolean
  def staticChanged: Boolean
  def connectParams: Map[String, Json]
```

- [ ] **Step 5: Consolidate duplicate stale component section only if local**

Search within `doc/public-api-reference.md` for duplicate `### LiveComponent[Props, Msg, Model]` headings.

Run: `rg -n "LiveComponent\[Props, Msg, Model\]" doc/public-api-reference.md`

Expected before edit: at least two matches.

If the later section repeats stale signatures or contradicts the first current section, delete the later duplicate section body only when it is clearly bounded by the next heading. If the boundary is unclear, leave it and add this short note at the later heading:

```markdown
This section is retained for conceptual guidance. The authoritative signature is
the earlier `LiveComponent[Props, Msg, Model]` API summary.
```

- [ ] **Step 6: Verify stale text is gone**

Run: `rg -n "link\.patch\(codec|link\.patchReplace\(codec|LiveUploadWriterState private\[scalive\]|connectParams" doc/public-api-reference.md`

Expected: no matches for `link.patch(codec`, `link.patchReplace(codec`, or `LiveUploadWriterState private[scalive]`; at least one match for `connectParams`.

- [ ] **Step 7: Review documentation diff**

Run: `git diff -- doc/public-api-reference.md README.md`

Expected: doc changes are narrow and match the implemented API.

---

### Task 5: Final Verification

**Files:**
- Verify all files changed by Tasks 1-4.

**Interfaces:**
- Consumes: all code and docs from prior tasks
- Produces: verified working tree ready for user review

- [ ] **Step 1: Run focused code tests**

Run: `mill --ticker false scalive.test.testOnly scalive.HtmlMessageTypeSafetySpec scalive.upload.LiveUploadSpec`

Expected: PASS.

- [ ] **Step 2: Run full scalive test module**

Run: `mill --ticker false scalive.test`

Expected: PASS.

- [ ] **Step 3: Run full project tests if time allows**

Run: `mill --ticker false __.test`

Expected: PASS. If this is too slow or fails outside the touched slice, record the failure output and the focused test result.

- [ ] **Step 4: Inspect final status and diff**

Run: `git status --short`

Expected: changed files are limited to:

```text
README.md
doc/public-api-reference.md
doc/superpowers/specs/2026-07-01-high-priority-api-polish-design.md
doc/superpowers/plans/2026-07-01-high-priority-api-polish.md
scalive/src/scalive/HtmlElement.scala
scalive/src/scalive/upload/LiveUpload.scala
scalive/test/src/scalive/HtmlMessageTypeSafetySpec.scala
scalive/test/src/scalive/upload/LiveUploadSpec.scala
```

Run: `git diff`

Expected: no unrelated changes and no typed outbound routing implementation.

---

## Self-Review

Spec coverage:

- Safe attribute binding helpers are covered by Task 1.
- Public upload writer state construction and access are covered by Task 2.
- Root newcomer README is covered by Task 3.
- Public API reference freshness is covered by Task 4.
- Verification is covered by Task 5.
- Typed outbound routes, broad string wrappers, stream state rename, and broad docs overhaul are explicitly out of scope.

Placeholder scan:

- The plan contains no `TBD`, `TODO`, or unbounded implementation instructions.
- Code steps include concrete snippets and verification commands.

Type consistency:

- `withValueOption`, `withBoolValueOption`, `withValue`, and `withBoolValue` signatures match across implementation, tests, and docs.
- `LiveUploadWriterState(value: Any)` and `valueAs[A]` match across implementation, tests, and docs, with a documented `ClassTag` fallback if compilation requires it.
