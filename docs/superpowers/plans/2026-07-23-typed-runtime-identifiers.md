# Typed Runtime Identifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace repeated public string identifiers with typed declarations for uploads, async tasks, subscriptions, flash kinds, and client event payload contracts.

**Architecture:** Add domain-local opaque key types with explicit `.value` accessors, then change public lifecycle facades and application-facing values to consume those types. Keep runtime maps and wire payloads string-based, unwrapping keys at public-to-runtime boundaries and wrapping strings when they re-enter application-facing APIs.

**Tech Stack:** Scala 3.7.3, ZIO 2, ZIO JSON, ZIO Test, Mill.

## Global Constraints

- Add exactly these public types: `UploadKey`, `AsyncKey[A]`, `SubscriptionKey`, `FlashKind`, and `ClientEvent[A]`.
- Implement each type as an opaque type over `String` with `apply(String)` and explicit `.value` access.
- Keep `AsyncKey[A]` and `ClientEvent[A]` invariant.
- Add no implicit conversions between strings and typed identifiers.
- Remove raw-string overloads from primary public APIs; Scalive is alpha and this is intentionally source-breaking.
- Keep runtime storage and wire formats string-based where they are not application-facing.
- Add no identifier validation or new runtime failures.
- Preserve existing runtime ownership, component scoping, replacement, cancellation, and encoding behavior.
- Typed client events guarantee Scala-side payload consistency only; JavaScript remains an unchecked boundary.
- Keep lifecycle hook IDs, client `phx-hook` names, selectors, DOM IDs, HTML names, and unsafe paths as strings.
- Do not create commits unless the user explicitly requests them.

---

### Task 1: Add Domain Identifier Types

**Files:**
- Create: `scalive/src/scalive/AsyncKey.scala`
- Create: `scalive/src/scalive/ClientEvent.scala`
- Create: `scalive/src/scalive/FlashKind.scala`
- Create: `scalive/src/scalive/SubscriptionKey.scala`
- Create: `scalive/src/scalive/upload/UploadKey.scala`
- Modify: `scalive/src/scalive/upload/LiveUpload.scala:167-182`
- Create: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`

**Interfaces:**
- Consumes: package-level `scalive.*` exports and `scalive.upload.api.*` export convention.
- Produces: `AsyncKey[A]`, `ClientEvent[A]`, `FlashKind`, `SubscriptionKey`, and exported `UploadKey`, each with `apply(String)` and `.value: String`.

- [ ] **Step 1: Add a failing external API test for constructors, explicit access, and string opacity**

Create `RuntimeIdentifierTypesSpec.scala` outside the `scalive` package-private boundary:

```scala
package scaliveapi

import zio.test.*

import scalive.*

object RuntimeIdentifierTypesSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeIdentifierTypesSpec")(
    test("runtime identifiers expose explicit string values") {
      assertTrue(
        AsyncKey[Int]("load").value == "load",
        ClientEvent[Int]("counter:changed").value == "counter:changed",
        FlashKind("info").value == "info",
        SubscriptionKey("clock").value == "clock",
        UploadKey("avatar").value == "avatar"
      )
    },
    test("runtime identifiers do not accept or become strings implicitly") {
      val stringToKeyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: UploadKey = "avatar"
      """)
      val keyToStringErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val value: String = FlashKind("info")
      """)
      val crossFamilyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: UploadKey = FlashKind("avatar")
      """)
      val asyncVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val key: AsyncKey[Any] = AsyncKey[String]("load")
      """)
      val eventVarianceErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val event: ClientEvent[Any] = ClientEvent[String]("changed")
      """)

      assertTrue(
        stringToKeyErrors.nonEmpty,
        keyToStringErrors.nonEmpty,
        crossFamilyErrors.nonEmpty,
        asyncVarianceErrors.nonEmpty,
        eventVarianceErrors.nonEmpty
      )
    }
  )
end RuntimeIdentifierTypesSpec
```

- [ ] **Step 2: Run the API test and verify it fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: compilation fails because the five identifier types do not exist.

- [ ] **Step 3: Implement the four root-package opaque types**

Create one focused file per domain. Use this exact shape, changing only the type and companion names:

```scala
package scalive

opaque type AsyncKey[A] = String

object AsyncKey:
  def apply[A](value: String): AsyncKey[A] = value

  extension [A](key: AsyncKey[A])
    def value: String = key
```

Create `ClientEvent.scala`:

```scala
package scalive

opaque type ClientEvent[A] = String

object ClientEvent:
  def apply[A](value: String): ClientEvent[A] = value

  extension [A](event: ClientEvent[A])
    def value: String = event
```

Create `FlashKind.scala`:

```scala
package scalive

opaque type FlashKind = String

object FlashKind:
  def apply(value: String): FlashKind = value

  extension (kind: FlashKind)
    def value: String = kind
```

Create `SubscriptionKey.scala`:

```scala
package scalive

opaque type SubscriptionKey = String

object SubscriptionKey:
  def apply(value: String): SubscriptionKey = value

  extension (key: SubscriptionKey)
    def value: String = key
```

- [ ] **Step 4: Implement and export `UploadKey`**

Create `upload/UploadKey.scala`:

```scala
package scalive
package upload

opaque type UploadKey = String

object UploadKey:
  def apply(value: String): UploadKey = value

  extension (key: UploadKey)
    def value: String = key
```

Add `UploadKey` to the existing `object api` export list in `LiveUpload.scala` so `import scalive.*` exposes it.

- [ ] **Step 5: Run the external API test and verify it passes**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: `RuntimeIdentifierTypesSpec` passes.

---

### Task 2: Type Async Task Keys

**Files:**
- Modify: `scalive/src/scalive/LiveContext.scala:139-141, 368-373`
- Modify: `scalive/src/scalive/LiveAsync.scala:61-71`
- Modify: `scalive/src/scalive/socket/SocketOutbound.scala:138-209`
- Modify: `scalive/src/scalive/socket/SocketComponentRuntime.scala:121-143`
- Modify: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`
- Modify: `scalive/test/src/scalive/AsyncSpec.scala`
- Modify: `scalive/test/src/scalive/LifecycleHookSpec.scala`

**Interfaces:**
- Consumes: `AsyncKey[A]` from Task 1 and the existing string-based `LiveAsyncRuntime`.
- Produces: `Async.start[A](key: AsyncKey[A])`, `Async.cancel[A](key: AsyncKey[A])`, and `LiveAsyncEvent.name: AsyncKey[Any]`.

- [ ] **Step 1: Add failing compile-time tests for async result and raw-string mismatches**

Append this test to `RuntimeIdentifierTypesSpec`:

```scala
test("async keys fix task result types and reject raw names") {
  val resultErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.*
    def start(ctx: MountContext[Unit, Unit]) =
      val key = AsyncKey[Int]("load")
      ctx.async.start(key)(ZIO.succeed("wrong"))(_ => ())
  """)
  val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.*
    def start(ctx: MountContext[Unit, Unit]) =
      ctx.async.start("load")(ZIO.succeed(1))(_ => ())
  """)

  assertTrue(resultErrors.nonEmpty, rawNameErrors.nonEmpty)
}
```

- [ ] **Step 2: Run the async API test and verify it fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: the new assertion fails because raw strings still compile and `AsyncKey[A]` does not yet constrain `Async.start`.

- [ ] **Step 3: Change the async facade and boundary conversion**

Replace the public and runtime facade signatures in `LiveContext.scala`:

```scala
trait Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: A => Msg): Task[Unit]
  def cancel[A](key: AsyncKey[A]): Task[Unit]
```

```scala
final private class RuntimeAsync[Msg](runtime: LiveContext) extends Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: A => Msg): Task[Unit] =
    runtime.async.start(key.value)(task)(toMsg)

  def cancel[A](key: AsyncKey[A]): Task[Unit] =
    runtime.async.cancel(key.value)
```

Keep `LiveAsyncRuntime` and `SocketAsyncRuntime` string-based.

- [ ] **Step 4: Propagate typed keys through public async events**

Change `LiveAsyncEvent` to:

```scala
final case class LiveAsyncEvent[+Msg](
  name: AsyncKey[Any],
  result: LiveAsyncResult[Msg])
```

At each internal event construction in `SocketOutbound.scala` and `SocketComponentRuntime.scala`, wrap the runtime string:

```scala
LiveAsyncEvent(AsyncKey[Any](name), LiveAsyncResult.Succeeded(msg))
LiveAsyncEvent(AsyncKey[Any](name), LiveAsyncResult.Failed(cause))
```

Do not change internal completion records or map keys.

- [ ] **Step 5: Migrate async tests to declarations with exact result types**

In `AsyncSpec`, replace the string-valued `Tasks` members with:

```scala
private object Tasks:
  val Load     = AsyncKey[String]("load")
  val Patch    = AsyncKey[String]("patch")
  val Navigate = AsyncKey[Unit]("navigate")
  val Redirect = AsyncKey[Unit]("redirect")
  val Flash    = AsyncKey[String]("flash")
```

Convert every other task-name constant according to its effect result, for example:

```scala
private val TaskName = AsyncKey[String]("component-update-task")
```

In async-hook assertions that compare event names, compare against the typed declaration rather than a string. In `LifecycleHookSpec`, declare `AsyncKey[String]("load")` next to the LiveView and use it for both start and hook assertions.

- [ ] **Step 6: Run focused async tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec scalive.AsyncSpec scalive.LifecycleHookSpec
```

Expected: all three suites pass, including replacement, cancellation, failure hooks, and component async behavior.

---

### Task 3: Type Subscription Keys

**Files:**
- Modify: `scalive/src/scalive/LiveContext.scala:143-146, 375-383`
- Modify: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`
- Modify: `scalive/test/src/scalive/LifecycleHookSpec.scala`
- Modify: `scalive/test/src/scalive/SocketSpec.scala`
- Modify: `scalive/test/src/scalive/ClientEventsSpec.scala`

**Interfaces:**
- Consumes: `SubscriptionKey` from Task 1 and the existing string-based `SubscriptionRuntime`.
- Produces: typed `Subscriptions.start`, `replace`, and `cancel` methods.

- [ ] **Step 1: Add a failing compile-time test rejecting raw subscription names**

Append:

```scala
test("subscription operations reject raw names") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.stream.ZStream
    def start(ctx: MountContext[Unit, Unit]) =
      ctx.subscriptions.start("clock")(ZStream.succeed(()))
  """)

  assertTrue(errors.nonEmpty)
}
```

- [ ] **Step 2: Run the API test and verify the new assertion fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: the assertion fails because `Subscriptions.start` still accepts `String`.

- [ ] **Step 3: Change the subscription facade and unwrap keys at the runtime boundary**

Use these signatures:

```scala
trait Subscriptions[Msg]:
  def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): Task[Unit]
  def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): Task[Unit]
  def cancel(key: SubscriptionKey): Task[Unit]
```

Implement all three facade methods with `key.value`:

```scala
final private class RuntimeSubscriptions[Msg](runtime: LiveContext) extends Subscriptions[Msg]:
  private def subscriptions = runtime.subscriptions.asInstanceOf[SubscriptionRuntime[Msg]]

  def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]) =
    subscriptions.start(key.value)(stream)

  def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]) =
    subscriptions.replace(key.value)(stream)

  def cancel(key: SubscriptionKey) =
    subscriptions.cancel(key.value)
```

Keep `SubscriptionRuntime`, `SocketSubscriptionRuntime`, and their maps string-based.

- [ ] **Step 4: Migrate subscription call sites in focused tests**

Declare one key per logical subscription and reuse it:

```scala
private val TickSubscription = SubscriptionKey("tick")
```

Update `LifecycleHookSpec`, `SocketSpec`, and `ClientEventsSpec` so every `start`, `replace`, and `cancel` receives a `SubscriptionKey`. Preserve all existing stream values and assertions.

- [ ] **Step 5: Run focused subscription tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec scalive.LifecycleHookSpec scalive.SocketSpec scalive.ClientEventsSpec
```

Expected: all suites pass, including duplicate-start errors, replacement, cancellation, and message delivery.

---

### Task 4: Add Typed Client Event Payload Contracts

**Files:**
- Modify: `scalive/src/scalive/LiveContext.scala:148-150, 385-396`
- Modify: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`
- Modify: `scalive/test/src/scalive/ClientEventsSpec.scala`

**Interfaces:**
- Consumes: invariant `ClientEvent[A]`, `JsonEncoder[A]`, and string-based `ClientEventRuntime.push`.
- Produces: `Client.push[A: JsonEncoder](event: ClientEvent[A], payload: A)` and no public `pushEvent(String, A)` method.

- [ ] **Step 1: Add failing compile-time tests for client event payloads and raw names**

Append:

```scala
test("client events fix payload types and reject raw names") {
  val payloadErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.json.*
    case class Payload(value: Int) derives JsonEncoder
    val event = ClientEvent[Payload]("counter:changed")
    def push(ctx: MessageContext[Unit, Unit]) = ctx.client.push(event, "wrong")
  """)
  val rawNameErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    def push(ctx: MessageContext[Unit, Unit]) = ctx.client.pushEvent("ready", 1)
  """)

  assertTrue(payloadErrors.nonEmpty, rawNameErrors.nonEmpty)
}
```

- [ ] **Step 2: Run the API test and verify it fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: compilation or the new assertions fail because `Client.push` does not exist and `pushEvent` remains public.

- [ ] **Step 3: Replace the public client event method and preserve runtime encoding**

Change the facade to:

```scala
trait Client:
  def push[A: JsonEncoder](event: ClientEvent[A], payload: A): Task[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit]
```

Implement the boundary conversion and internal JS event declaration:

```scala
final private case class PushJsPayload(cmd: String) derives JsonEncoder
private val PushJsEvent = ClientEvent[PushJsPayload]("js:exec")

final private class RuntimeClient(runtime: LiveContext) extends Client:
  def push[A: JsonEncoder](event: ClientEvent[A], payload: A): Task[Unit] =
    payload.toJsonAST match
      case Right(encoded) => runtime.clientEvents.push(event.value, encoded)
      case Left(error)    =>
        ZIO.fail(
          new IllegalArgumentException(
            s"Could not encode client event '${event.value}': $error"
          )
        )

  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit] =
    import JSCommands.JSCommand.given
    push(PushJsEvent, PushJsPayload(js.toJson))
```

Keep `ClientEventRuntime`, `Diff.Event`, and wire event names string-based.

- [ ] **Step 4: Migrate client event runtime tests to typed payload declarations**

At the top of `ClientEventsSpec`, declare explicit payload contracts:

```scala
private final case class ReadyPayload(ok: Boolean) derives JsonEncoder
private final case class TickPayload(value: Int) derives JsonEncoder

private val ReadyEvent     = ClientEvent[ReadyPayload]("ready")
private val TickEvent      = ClientEvent[TickPayload]("tick")
private val ComponentEvent = ClientEvent[ReadyPayload]("component")
```

Replace pushes with:

```scala
ctx.client.push(ReadyEvent, ReadyPayload(ok = true))
ctx.client.push(TickEvent, TickPayload(value = 1))
ctx.client.push(ComponentEvent, ReadyPayload(ok = true))
```

Keep assertions on encoded string names and JSON payloads unchanged; they prove the wire format did not change.

- [ ] **Step 5: Run focused client event tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec scalive.ClientEventsSpec
```

Expected: both suites pass, including `js:exec`, component pushes, and exact encoded event JSON.

---

### Task 5: Type Upload Keys Across Public Upload Values

**Files:**
- Modify: `scalive/src/scalive/LiveContext.scala:110-117, 330-341`
- Modify: `scalive/src/scalive/upload/LiveUpload.scala:79-165`
- Modify: `scalive/src/scalive/socket/SocketUploadShared.scala:44-55, 100-116`
- Modify: `scalive/src/scalive/socket/SocketUploadRuntime.scala:148-175`
- Modify: `scalive/src/scalive/socket/SocketUploadProgressBinding.scala:130-145`
- Modify: `scalive/src/scalive/defs/components/Components.scala:21-35`
- Modify: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`
- Modify: `scalive/test/src/scalive/socket/SocketUploadSpec.scala`
- Modify: `scalive/test/src/scalive/upload/LiveUploadSpec.scala`
- Modify: `scalive/test/src/scalive/defs/components/ComponentsSpec.scala`

**Interfaces:**
- Consumes: `UploadKey` and the existing string-based `UploadRuntime` and socket state.
- Produces: typed upload facade methods, `LiveUpload.name: UploadKey`, and typed writer/progress callback names.

- [ ] **Step 1: Add a failing compile-time test rejecting raw upload names**

Append:

```scala
test("upload operations reject raw names") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    def get(ctx: MessageContext[Unit, Unit]) = ctx.uploads.get("avatar")
  """)

  assertTrue(errors.nonEmpty)
}
```

- [ ] **Step 2: Run the API test and verify the new assertion fails**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: the assertion fails because upload facade methods still accept strings.

- [ ] **Step 3: Type every upload-name facade operation**

Change only operations that consume an upload name; entry references remain strings:

```scala
trait Uploads:
  def allow(key: UploadKey, options: LiveUploadOptions): Task[LiveUpload]
  def disallow(key: UploadKey): Task[Unit]
  def get(key: UploadKey): Task[Option[LiveUpload]]
  def cancel(key: UploadKey, entryRef: String): Task[Unit]
  def consumeCompleted(key: UploadKey): Task[List[LiveUploadedEntry]]
  def consume(entryRef: String): Task[Option[LiveUploadedEntry]]
  def drop(entryRef: String): Task[Unit]
```

In `RuntimeUploads`, unwrap every key before calling `UploadRuntime`:

```scala
final private class RuntimeUploads(runtime: LiveContext) extends Uploads:
  def allow(key: UploadKey, options: LiveUploadOptions) =
    runtime.uploads.allow(key.value, options)
  def disallow(key: UploadKey) = runtime.uploads.disallow(key.value)
  def get(key: UploadKey) = runtime.uploads.get(key.value)
  def cancel(key: UploadKey, entryRef: String) = runtime.uploads.cancel(key.value, entryRef)
  def consumeCompleted(key: UploadKey) = runtime.uploads.consumeCompleted(key.value)
  def consume(entryRef: String) = runtime.uploads.consume(entryRef)
  def drop(entryRef: String) = runtime.uploads.drop(entryRef)
```

Do not change the internal `UploadRuntime` signatures or socket map key types.

- [ ] **Step 4: Propagate `UploadKey` through application-facing upload values and callbacks**

Change these public declarations:

```scala
final case class LiveUpload(
  name: UploadKey,
  ref: String,
  accept: LiveUploadAccept,
  maxEntries: Int,
  maxFileSize: Long,
  chunkSize: Int,
  chunkTimeout: Int,
  autoUpload: Boolean,
  external: Boolean,
  entries: List[LiveUploadEntry],
  errors: List[LiveUploadError])

trait LiveUploadWriter:
  def init(uploadKey: UploadKey, entry: LiveExternalUploadEntry): Task[LiveUploadWriterState]
  def meta(state: LiveUploadWriterState): Json.Obj
  def writeChunk(data: Chunk[Byte], state: LiveUploadWriterState): Task[LiveUploadWriterState]
  def close(
    state: LiveUploadWriterState,
    reason: LiveUploadWriterCloseReason
  ): Task[LiveUploadWriterState]

trait LiveUploadProgress:
  def onProgress(uploadKey: UploadKey, entry: LiveUploadEntry): Task[Unit]
```

Update `LiveUploadWriter.InMemory` to the same signature. Do not change file-name fields such as `LiveUploadedEntry.name` or `LiveExternalUploadEntry.name`.

- [ ] **Step 5: Wrap and unwrap keys at socket and HTML boundaries**

In `SocketUploadShared.buildLiveUpload`, replace the `name` assignment with the public key:

```scala
name = UploadKey(config.name)
```

Wrap internal upload-name strings before writer and progress callbacks:

```scala
writer.init(UploadKey(entry.uploadName), toExternalUploadEntry(entry))
progress.onProgress(UploadKey(entry.uploadName), toLiveUploadEntry(entry))
```

Adapt scoped public upload copies without changing the internal scope format:

```scala
private def unscoped(upload: LiveUpload): LiveUpload =
  val name = upload.name.value
  if name.startsWith(scope) then upload.copy(name = UploadKey(name.drop(scope.length)))
  else upload
```

In `Components.liveFileInput`, render the wire name explicitly:

```scala
nameAttr := upload.name.value
```

- [ ] **Step 6: Migrate focused upload tests and fixtures**

Replace upload-name test constants with typed declarations:

```scala
private val Upload = UploadKey("avatar")
```

Update direct `LiveUpload` construction to use `name = UploadKey("avatar")`. Update custom writer and progress implementations so their callback parameter is `UploadKey`, and assert `.value` only where the test intentionally checks the wire string.

- [ ] **Step 7: Run focused upload tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec scalive.socket.SocketUploadSpec scalive.upload.LiveUploadSpec scalive.defs.components.ComponentsSpec
```

Expected: all suites pass, including scoped component uploads, writer callbacks, progress callbacks, cancellation, consumption, and rendered input names.

---

### Task 6: Type Flash Kinds Across Mutation And Rendering

**Files:**
- Modify: `scalive/src/scalive/LiveContext.scala:103-108, 323-328`
- Modify: `scalive/src/scalive/Scalive.scala:43-44`
- Modify: `scalive/test/src/scaliveapi/RuntimeIdentifierTypesSpec.scala`
- Modify: `scalive/test/src/scalive/FlashSpec.scala`
- Modify: `scalive/test/src/scalive/AsyncSpec.scala`

**Interfaces:**
- Consumes: `FlashKind`, string-based `FlashRuntime`, and internal `Mod.Content.Flash`.
- Produces: typed `Flash.put`, `clear`, `get`, `snapshot`, and render-time `flash` helper.

- [ ] **Step 1: Add failing compile-time tests for flash mutation and rendering**

Append:

```scala
test("flash APIs reject raw kinds") {
  val contextErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    def put(ctx: MessageContext[Unit, Unit]) = ctx.flash.put("info", "Saved")
  """)
  val renderErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    val content = flash("info")(message => div(message))
  """)

  assertTrue(contextErrors.nonEmpty, renderErrors.nonEmpty)
}
```

- [ ] **Step 2: Run the API test and verify the new assertions fail**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec
```

Expected: the assertions fail because flash APIs still accept strings.

- [ ] **Step 3: Change the flash facade and typed snapshot**

Use these public signatures:

```scala
trait Flash:
  def put(kind: FlashKind, message: String): Task[Unit]
  def clear(kind: FlashKind): Task[Unit]
  def clearAll: Task[Unit]
  def get(kind: FlashKind): Task[Option[String]]
  def snapshot: Task[Map[FlashKind, String]]
```

Unwrap mutation and lookup keys in `RuntimeFlash`, and wrap snapshot keys:

```scala
def put(kind: FlashKind, message: String) = runtime.flash.put(kind.value, message)
def clear(kind: FlashKind) = runtime.flash.clear(kind.value)
def clearAll = runtime.flash.clearAll
def get(kind: FlashKind) = runtime.flash.get(kind.value)
def snapshot = runtime.flash.snapshot.map(_.map((kind, message) => FlashKind(kind) -> message))
```

Keep `FlashRuntime`, token JSON, socket state, and inbound clear-flash protocol strings unchanged.

- [ ] **Step 4: Type the render helper while keeping render internals string-based**

Change `Scalive.scala` to:

```scala
def flash(kind: FlashKind)(f: String => HtmlElement[Nothing]): Mod[Nothing] =
  Mod.Content.Flash(kind.value, f)
```

Do not change `Mod.Content.Flash` or component rendering internals; they are protocol/render boundaries rather than application-facing APIs.

- [ ] **Step 5: Migrate flash tests to shared typed declarations**

At the top of `FlashSpec`, define:

```scala
private val Info  = FlashKind("info")
private val Error = FlashKind("error")
```

Use `Info` and `Error` for every `put`, `get`, `clear`, and `flash` call. Browser payloads and `phx.value("key")` remain strings; use `Info.value` when constructing them:

```scala
phx.value("key") := Info.value
Json.Obj("key" -> Json.Str(Info.value))
```

Apply the same typed constants to flash use in `AsyncSpec`.

Add an explicit snapshot boundary test to `FlashSpec`:

```scala
test("snapshot exposes typed flash kinds") {
  for
    ref <- Ref.make(scalive.socket.FlashRuntimeState.empty)
    runtime = new scalive.socket.SocketFlashRuntime(ref)
    ctx = LiveContext(staticChanged = false, flash = runtime).messageContext[Unit, Unit]
    _        <- ctx.flash.put(Info, "Saved")
    snapshot <- ctx.flash.snapshot
  yield assertTrue(snapshot == Map(Info -> "Saved"))
}
```

- [ ] **Step 6: Run focused flash tests**

Run:

```bash
mill --ticker false scalive.test.testOnly scaliveapi.RuntimeIdentifierTypesSpec scalive.FlashSpec scalive.AsyncSpec
```

Expected: all suites pass, including client clear, redirects, patches, navigation flash, nested views, components, and typed snapshots.

---

### Task 7: Migrate Applications, Refresh Documentation, And Verify

**Files:**
- Modify: `example/src/CounterLiveView.scala`
- Modify: `e2eApp/src/UploadLiveView.scala`
- Modify: `e2eApp/src/IssueLiveViews.scala`
- Modify: `doc/public-api-reference.md`
- Modify: `doc/api-improvement-ideas.md`
- Modify: `doc/phase-context-api-design.md`
- Modify: `doc/user-facing-api-assessment.md`

**Interfaces:**
- Consumes: all typed public APIs from Tasks 1-6.
- Produces: a compiling workspace, examples demonstrating declaration-and-reuse, and documentation that states the exact safety boundary.

- [ ] **Step 1: Find every remaining raw public identifier call site**

Run:

```bash
rg 'pushEvent\(|ctx\.(async|subscriptions)\.(start|replace|cancel)\("|ctx\.flash\.(put|get|clear)\("|flash\("|uploads\.(allow|disallow|get|cancel|consumeCompleted)\(' --glob '*.scala'
```

Expected: matches identify all remaining application, fixture, and test migrations. Internal runtime methods that intentionally remain string-based are not changed merely because they match a broad search.

- [ ] **Step 2: Migrate examples and E2E fixtures to declaration-and-reuse**

Declare values next to each owning view or component, with associated types matching actual effects and payloads:

```scala
private val CounterSubscription = SubscriptionKey("counter")
private val AvatarUpload        = UploadKey("avatar")
private val LoadUser            = AsyncKey[User]("load-user")
private val InfoFlash           = FlashKind("info")
private val UploadNextEvent     = ClientEvent[Map[String, String]]("upload_send_next_file")
```

Replace direct upload construction with typed `name` values, update custom upload callback signatures, and use `.value` only at explicit string boundaries such as synthetic browser payloads and manually built upload references.

- [ ] **Step 3: Compile all modules to catch unmigrated source call sites**

Run:

```bash
mill --ticker false __.compile
```

Expected: all modules compile. Any error passing `String` to a typed facade is an unmigrated application-facing call site; convert it to a declared key rather than adding an overload.

- [ ] **Step 4: Update the public API reference and historical phase-context examples**

Document the exact signatures:

```scala
ctx.async.start[A](key: AsyncKey[A])(task: Task[A])(toMsg: A => Msg)
ctx.async.cancel[A](key: AsyncKey[A])
ctx.subscriptions.start(key: SubscriptionKey)(stream)
ctx.uploads.allow(key: UploadKey, options)
ctx.flash.put(kind: FlashKind, message)
ctx.client.push(event: ClientEvent[A], payload: A)
```

Explain that client event payloads are checked across Scala push sites while JavaScript name and JSON handling remain unchecked. Replace examples that repeat raw names with one declaration and multiple uses. Keep selector, DOM ID, hook ID, and unsafe path examples as strings.

- [ ] **Step 5: Resolve improvement notes and narrow the assessment finding**

In `api-improvement-ideas.md`, remove completed proposals for typed async keys and repeated high-risk string wrappers. Preserve any separate future ideas about validated selectors or generated client contracts.

In `user-facing-api-assessment.md`, change the repeated-string finding and risk-register entry to state that durable resource keys and Scala payload contracts are typed. Explicitly retain the deliberate boundaries: lifecycle hook IDs are structurally namespaced, while selectors, DOM IDs, client hook names, and unsafe paths remain strings because nominal wrappers would not validate them.

- [ ] **Step 6: Format and apply project fixes**

Run:

```bash
mill --ticker false __.reformat + __.fix
```

Expected: command exits successfully and only reformats or fixes intended project files.

- [ ] **Step 7: Run focused regression suites after formatting**

Run:

```bash
mill --ticker false scalive.test.testOnly \
  scaliveapi.RuntimeIdentifierTypesSpec \
  scalive.AsyncSpec \
  scalive.ClientEventsSpec \
  scalive.FlashSpec \
  scalive.LifecycleHookSpec \
  scalive.SocketSpec \
  scalive.socket.SocketUploadSpec \
  scalive.upload.LiveUploadSpec \
  scalive.defs.components.ComponentsSpec
```

Expected: all listed suites pass with zero failures.

- [ ] **Step 8: Run full project verification**

Run:

```bash
mill --ticker false __.test
```

Expected: all project tests pass with zero failures.

- [ ] **Step 9: Inspect the final diff for accidental compatibility paths or scope drift**

Run:

```bash
git diff --check
```

Expected: no whitespace errors; changes are limited to identifier types, their public/runtime boundaries, call-site migrations, tests, examples, and documentation. Confirm there are no raw-string overloads, implicit conversions, hook key types, selector wrappers, validation changes, or wire-format changes.
