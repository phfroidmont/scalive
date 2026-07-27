# Typed Outbound Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive outbound LiveView locations from the same typed route builders used for inbound matching and require those locations in safe navigation APIs.

**Architecture:** `LiveLocation` is a validated relative URL produced by route builders. `LiveParamsCodec` becomes bidirectional, while `LiveParamsDecoder` and a route-builder capability marker preserve explicitly inbound-only routes. Links, lifecycle contexts, JS commands, and mount failures consume `LiveLocation`; raw strings remain available only through explicitly unsafe methods and continue through the existing navigation runtime unchanged.

**Tech Stack:** Scala 3.7.3, ZIO 2, ZIO HTTP 3.10.1 `PathCodec`/`QueryCodec`, ZIO Schema, ZIO Test, Mill, Phoenix LiveView upstream Playwright fixtures.

## Global Constraints

- Scalive is Alpha; prefer the best user-facing API over backward compatibility.
- A named route builder is the single source of truth for inbound path/query decoding and outbound path/query encoding.
- Do not add a type parameter to `LiveView` or `RoutedLiveView`.
- Safe navigation methods accept only `LiveLocation`; do not add implicit `String` or `URL` conversions.
- Keep typed query-only patches, global route registries, generated route names, current-view patch proofs, and live-session proofs out of scope.
- Preserve existing Phoenix payloads and browser behavior.
- Use ASCII in new source and documentation.
- Follow TDD: add a focused failing test, run it, implement the smallest behavior, then rerun it.
- Run `mill --ticker false __.reformat + __.fix` before final verification.
- Do not commit unless the user explicitly asks. Treat each task boundary as the suggested commit boundary.

---

## File Structure

- Create `scalive/src/scalive/LiveLocation.scala`: nominal location value, structured encode errors, checked/direct fragment handling, and package-private path/query construction.
- Modify `scalive/src/scalive/LiveParamsCodec.scala`: split decode-only and bidirectional contracts and encode path/query components.
- Modify `scalive/src/scalive/LiveRouteParamsRuntime.scala`: depend on the decode-only contract because runtime params handling never encodes.
- Modify `scalive/src/scalive/routing/LiveRouteDsl.scala`: expose route-builder location construction, bidirectional mapping, decode-only builders, and compile-time outbound capability.
- Modify `scalive/src/scalive/Scalive.scala`: safe and explicitly unsafe link helpers.
- Modify `scalive/src/scalive/JS.scala`: safe and explicitly unsafe navigation commands.
- Modify `scalive/src/scalive/LiveContext.scala`: safe and explicitly unsafe lifecycle navigation.
- Modify `scalive/src/scalive/LiveMountAspect.scala`: typed and explicitly unsafe mount-failure redirects.
- Modify `scalive/src/scalive/routing/LiveRoutesRuntime.scala`: serialize both mount-failure redirect variants.
- Create `scalive/test/src/scalive/LiveLocationSpec.scala`: codec and route-builder outbound tests.
- Create `scalive/test/src/scalive/NavigationApiSpec.scala`: link, JS, context, and compile-time safe/unsafe API tests.
- Modify existing route, socket, mount-aspect, navigation, flash, component, and async specs for the breaking API.
- Create `example/src/ExampleRoutes.scala`: named route builders for the beginner application.
- Create `e2eApp/src/E2ERoutes.scala`: named route builders shared by E2E route mounting and outbound callers.
- Modify example and E2E LiveViews to generate declared internal locations and mark exceptional raw destinations unsafe.
- Update the public API reference, API assessment, improvement notes, and approved design with final signatures.

---

### Task 1: Add Location And Bidirectional Params Primitives

**Files:**
- Create: `scalive/src/scalive/LiveLocation.scala`
- Modify: `scalive/src/scalive/LiveParamsCodec.scala`
- Modify: `scalive/src/scalive/LiveRouteParamsRuntime.scala`
- Create: `scalive/test/src/scalive/LiveLocationSpec.scala`
- Modify: `scalive/test/src/scalive/SocketSpec.scala`

**Interfaces:**
- Consumes: `zio.http.URL`, `Path`, `QueryParams`, `PathCodec[A]`, `QueryCodec[A]`, and `Combiner[L, R]`.
- Produces: `LiveLocation`, `LiveLocation.EncodeError`, `LiveLocation.EncodingException`, `LiveParamsDecoder[PathParams, Params]`, `LiveParamsCodec.Encoded[PathParams]`, and bidirectional `LiveParamsCodec[PathParams, Params]`.

- [ ] **Step 1: Add failing primitive encoding tests**

Create `LiveLocationSpec.scala` with tests that use the intended contracts before they exist:

```scala
package scalive

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.test.*

object LiveLocationSpec extends ZIOSpecDefault:
  private val usersPath = PathCodec.empty / "users" / PathCodec.string("id")

  override def spec = suite("LiveLocationSpec")(
    test("encodes path and query values") {
      val encoded = LiveParamsCodec.Encoded(
        pathParams = "a b",
        queryParams = QueryParams("tab" -> "settings & profile")
      )
      val href = LiveLocation.encode(usersPath, encoded).toOption.get.href
      val decoded = URL.decode(href).toOption.get

      assertTrue(
        href.startsWith("/users/a%20b?"),
        decoded.queryParam("tab").contains("settings & profile")
      )
    },
    test("round trips combined path and optional query params") {
      val codec = LiveParamsCodec.fromQuery[String, Option[String]](
        HttpCodec.query[String]("tab").optional
      )
      val url = URL.decode("/users/alice?tab=settings").toOption.get

      for decoded <- codec.decode("alice", url)
      yield assertTrue(
        decoded == ("alice", Some("settings")),
        codec.encode(decoded).exists(value =>
          value.pathParams == "alice" && value.queryParams.getAll("tab") == Chunk("settings")
        )
      )
    },
    test("returns path encode errors") {
      val positiveId = PathCodec
        .int("id")
        .transformOrFailRight(identity)(id => Either.cond(id > 0, id, "id must be positive"))

      assertTrue(
        LiveLocation
          .encode(positiveId, LiveParamsCodec.Encoded(-1, QueryParams.empty))
          .left
          .exists(_ == LiveLocation.EncodeError.Path("id must be positive"))
      )
    },
    test("returns query encode errors") {
      val failingQuery = HttpCodec
        .query[Int]("page")
        .transformOrFailRight(identity)(_ => Left("page cannot be encoded"))
        .asQuery
      val codec = LiveParamsCodec.fromQuery[Unit, Int](failingQuery)

      assertTrue(
        codec.encode(1).left.exists(_.isInstanceOf[LiveLocation.EncodeError.Query])
      )
    },
    test("adds encoded fragments with checked and direct APIs") {
      val location = LiveLocation
        .encode(usersPath, LiveParamsCodec.Encoded("alice", QueryParams.empty))
        .toOption
        .get

      assertTrue(
        location.withFragment("profile%20details").href == "/users/alice#profile%20details",
        location.withFragmentEither("%").isLeft,
        scala.util.Try(location.withFragment("%")).failed.toOption.exists(
          _.isInstanceOf[LiveLocation.EncodingException]
        )
      )
    }
  )
end LiveLocationSpec
```

- [ ] **Step 2: Run the new spec and confirm it fails**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveLocationSpec`

Expected: compilation fails because `LiveLocation`, `LiveParamsCodec.Encoded`, and `LiveParamsCodec.encode` do not exist.

- [ ] **Step 3: Implement `LiveLocation`**

Create `scalive/src/scalive/LiveLocation.scala` with this public shape and package-private encoder:

```scala
package scalive

import zio.http.*
import zio.http.codec.PathCodec

final class LiveLocation private[scalive] (private[scalive] val url: URL):
  def href: String = url.encode

  def withFragment(fragment: String): LiveLocation =
    withFragmentEither(fragment).fold(
      error => throw new LiveLocation.EncodingException(error),
      identity
    )

  def withFragmentEither(
    fragment: String
  ): Either[LiveLocation.EncodeError, LiveLocation] =
    URL
      .decode(s"#$fragment")
      .left
      .map(error => LiveLocation.EncodeError.Fragment(error.getMessage))
      .map(parsed => new LiveLocation(url.copy(fragment = parsed.fragment)))

object LiveLocation:
  enum EncodeError:
    case Path(details: String)
    case Query(cause: Throwable)
    case Fragment(details: String)

    def message: String = this match
      case Path(details)     => s"Could not encode route path: $details"
      case Query(cause)      => s"Could not encode route query: ${cause.getMessage}"
      case Fragment(details) => s"Could not encode route fragment: $details"

  final class EncodingException(val error: EncodeError)
      extends IllegalArgumentException(error.message)

  private[scalive] def encode[A](
    pathCodec: PathCodec[A],
    encoded: LiveParamsCodec.Encoded[A]
  ): Either[EncodeError, LiveLocation] =
    pathCodec
      .encode(encoded.pathParams)
      .left
      .map(EncodeError.Path.apply)
      .map(path => new LiveLocation(URL(path, queryParams = encoded.queryParams)))
end LiveLocation
```

- [ ] **Step 4: Split decode-only and bidirectional params contracts**

Replace the current single-purpose trait in `LiveParamsCodec.scala` with these contracts:

```scala
trait LiveParamsDecoder[PathParams, Params]:
  def decode(
    pathParams: PathParams,
    url: URL
  ): IO[LiveParamsCodec.DecodeError, Params]

  def mapDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveParamsDecoder[PathParams, Params2] =
    val self = this
    new LiveParamsDecoder[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL) =
        self.decode(pathParams, url).map(decodeParams)

trait LiveParamsCodec[PathParams, Params] extends LiveParamsDecoder[PathParams, Params]:
  def encode(
    params: Params
  ): Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[PathParams]]

  def imap[Params2](
    decodeParams: Params => Params2
  )(
    encodeParams: Params2 => Params
  ): LiveParamsCodec[PathParams, Params2] =
    val self = this
    new LiveParamsCodec[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL) =
        self.decode(pathParams, url).map(decodeParams)
      def encode(params: Params2) = self.encode(encodeParams(params))
```

Add `LiveParamsDecoder.custom`, then make all `LiveParamsCodec` constructors encode:

```scala
object LiveParamsDecoder:
  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[LiveParamsCodec.DecodeError | String, Params]
  ): LiveParamsDecoder[PathParams, Params] =
    new LiveParamsDecoder[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL) =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(LiveParamsCodec.normalizeDecodeError))

object LiveParamsCodec:
  final case class Encoded[PathParams](
    pathParams: PathParams,
    queryParams: QueryParams
  )

  def path[A]: LiveParamsCodec[A, A] =
    custom(
      decodeFn = (pathParams, _) => Right(pathParams),
      encodeFn = pathParams => Right(Encoded(pathParams, QueryParams.empty))
    )

  def fromQuery[PathParams, QueryParams](codec: QueryCodec[QueryParams])(
    using combiner: Combiner[PathParams, QueryParams]
  ): LiveParamsCodec[PathParams, combiner.Out] =
    new LiveParamsCodec[PathParams, combiner.Out]:
      def decode(pathParams: PathParams, url: URL) =
        codec
          .decodeRequest(Request.get(url))
          .map(queryParams => combiner.combine(pathParams, queryParams))
          .mapError(toDecodeError)

      def encode(params: combiner.Out) =
        try
          val (pathParams, queryParams) = combiner.separate(params)
          Right(Encoded(pathParams, codec.encodeRequest(queryParams).url.queryParams))
        catch
          case scala.util.control.NonFatal(cause) =>
            Left(LiveLocation.EncodeError.Query(cause))

  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[DecodeError | String, Params],
    encodeFn: Params => Either[LiveLocation.EncodeError, Encoded[PathParams]]
  ): LiveParamsCodec[PathParams, Params] =
    new LiveParamsCodec[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL) =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(normalizeDecodeError))
      def encode(params: Params) = encodeFn(params)
```

Keep `DecodeError`, `query`, `fromZioHttp`, `none`, and decode-error normalization. Change `normalizeDecodeError` to `private[scalive]` so `LiveParamsDecoder.custom` can reuse it without duplicating policy.

- [ ] **Step 5: Make params runtime decode-only**

In `LiveRouteParamsRuntime.scala`, change both `LiveParamsCodec[A, Params]` parameters to `LiveParamsDecoder[A, Params]`. Do not alter lifecycle ordering or error handling:

```scala
def routed[A, Msg, Model, Params](
  pathCodec: PathCodec[A],
  paramsDecoder: LiveParamsDecoder[A, Params]
): LiveRouteParamsRuntime[A, Msg, Model]
```

Rename the private helper parameter as well and keep its exact call flow:

```scala
decode(pathCodec, paramsDecoder, url)
  .flatMap(params =>
    routed.handleParams(hookModel, params, url, ctx.paramsContext[Msg, Model])
  )

private def decode[A, Params](
  pathCodec: PathCodec[A],
  paramsDecoder: LiveParamsDecoder[A, Params],
  url: URL
): IO[LiveParamsCodec.DecodeError, Params] =
  ZIO
    .fromEither(
      pathCodec.decode(url.path).left.map(error =>
        LiveParamsCodec.DecodeError(
          s"Could not decode path '${url.path.encode}' for route '${pathCodec.render}': $error"
        )
      )
    )
    .flatMap(pathParams => paramsDecoder.decode(pathParams, url))
```

In `SocketSpec.scala`, replace the URL-dependent custom codec with the decode-only contract so the test module compiles after `LiveParamsCodec.custom` becomes bidirectional:

```scala
LiveParamsDecoder.custom[Unit, (Option[String], String)](
  decodeFn = (_, url) => Right(url.queryParam("q") -> url.path.encode)
)
```

- [ ] **Step 6: Run focused primitive tests**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveLocationSpec`

Expected: `LiveLocationSpec` passes and the existing socket test sources compile against `LiveParamsDecoder`.

---

### Task 2: Expose Locations From Route Builders

**Files:**
- Modify: `scalive/src/scalive/routing/LiveRouteDsl.scala`
- Modify: `scalive/test/src/scalive/LiveLocationSpec.scala`
- Modify: `scalive/test/src/scalive/LiveRoutesTypeSafetySpec.scala`
- Modify: `scalive/test/src/scalive/LiveRoutesLifecycleSpec.scala`

**Interfaces:**
- Consumes: `LiveLocation.encode`, `LiveParamsDecoder`, `LiveParamsCodec`, and `LiveParamsCodec.Encoded` from Task 1.
- Produces: `location`, `locationEither`, `paramsDecodeOnly`, `mapParams(decode)(encode)`, `mapParamsDecodeOnly`, and compile-time `LiveRouteParamsCapability` state.

- [ ] **Step 1: Add failing route-builder behavior tests**

Extend `LiveLocationSpec`:

```scala
final case class UserLocation(id: String, tab: Option[String])

test("builds a mapped location from the same route declaration") {
  val userRoute =
    (live / "users" / PathCodec.string("id"))
      .queryOptional[String]("tab")
      .mapParams { case (id, tab) => UserLocation(id, tab) }(
        location => location.id -> location.tab
      )

  assertTrue(
    userRoute.location(UserLocation("alice", Some("settings"))).href ==
      "/users/alice?tab=settings",
    userRoute.location(UserLocation("alice", None)).href == "/users/alice",
    userRoute.locationEither(UserLocation("alice", None)).isRight
  )
},
test("builds Unit locations without an argument") {
  val home = live / "home"

  assertTrue(home.location.href == "/home", home.locationEither.isRight)
},
test("builds required query locations") {
  val search = (live / "search").query[Int]("page")

  assertTrue(search.location(2).href == "/search?page=2")
},
test("direct location wraps checked path failures") {
  val positiveId = PathCodec
    .int("id")
    .transformOrFailRight(identity)(id => Either.cond(id > 0, id, "id must be positive"))
  val route = live / "users" / positiveId

  assertTrue(
    route.locationEither(-1).isLeft,
    scala.util.Try(route.location(-1)).failed.toOption.exists(
      _.isInstanceOf[LiveLocation.EncodingException]
    )
  )
}
```

- [ ] **Step 2: Add failing capability tests**

Extend `LiveRoutesTypeSafetySpec` with one positive and one negative compile test:

```scala
test("encodable route params expose final-domain location methods") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.http.codec.PathCodec

    final case class UserLocation(id: Int, tab: Option[String])

    val route =
      (live / "users" / PathCodec.int("id"))
        .queryOptional[String]("tab")
        .mapParams { case (id, tab) => UserLocation(id, tab) }(
          location => location.id -> location.tab
        )

    val location: LiveLocation = route.location(UserLocation(42, Some("settings")))
  """)

  assertTrue(errors.isEmpty)
},
test("decode-only route params do not expose location methods") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*

    val route = live.paramsDecodeOnly(
      LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
    )

    route.location("/")
  """)

  assertTrue(errors.nonEmpty)
},
test("decode-only route params can still be mounted") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.*

    val route = live.paramsDecodeOnly(
      LiveParamsDecoder.custom[Unit, String]((_, url) => Right(url.path.encode))
    )

    val view = new RoutedLiveView[Unit, Unit, String]:
      def mount(ctx: MountContext) = ZIO.unit
      override def handleParams(
        model: Unit,
        params: String,
        url: zio.http.URL,
        ctx: ParamsContext
      ) = ZIO.succeed(model)
      def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
      def render(model: Unit): HtmlElement[Unit] = div()

    val mounted = route -> view
  """)

  assertTrue(errors.isEmpty)
}
```

- [ ] **Step 3: Run route-builder tests and confirm they fail**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveLocationSpec scalive.LiveRoutesTypeSafetySpec`

Expected: compilation fails because builders do not expose the new APIs or capability state.

- [ ] **Step 4: Add path-only builder construction**

In both `LiveRouteSeed[A]` and `LiveRouteBuilder[R, A, Need, Ctx]`, add:

```scala
def location(value: A): LiveLocation =
  locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
  LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

@targetName("unitLocation")
def location(using ev: A =:= Unit): LiveLocation =
  location(ev.flip(()))

@targetName("unitLocationEither")
def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
  locationEither(ev.flip(()))
```

Keep `pathCodec` constructor-private; callers reuse the builder rather than extracting its codec.

- [ ] **Step 5: Add route-params capability state**

Add public marker types because they appear in inferred public builder types:

```scala
sealed trait LiveRouteParamsCapability

object LiveRouteParamsCapability:
  sealed trait Encodable extends LiveRouteParamsCapability
  sealed trait DecodeOnly extends LiveRouteParamsCapability
```

Add a sixth `Capability` parameter to `LiveRouteParamsBuilder` and store the common decoder plus optional encoder:

```scala
final class LiveRouteParamsBuilder[
  R,
  A,
  -Need,
  Ctx,
  Params,
  Capability <: LiveRouteParamsCapability
] private[scalive] (
  pathCodec: PathCodec[A],
  paramsDecoder: LiveParamsDecoder[A, Params],
  paramsEncoder: Option[
    Params => Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[A]]
  ],
  mountPipeline: LiveMountPipeline[R, A, Need, Ctx],
  liveLayouts: List[LiveLayoutLayer[A, Ctx, ?]],
  rootLayout: Option[LiveRootLayoutLayer[A, Ctx, ?]],
  hasRouteMountAspect: Boolean
)
```

Update all builder return types and `@@` copies to preserve `Capability`. Encodable constructors pass `Some(codec.encode)`. Decode-only constructors pass `None`.

- [ ] **Step 6: Add encodable and decode-only builder methods**

On `LiveRouteSeed` and `LiveRouteBuilder`, keep `.params` and all query methods encodable, and add:

```scala
def paramsDecodeOnly[Params](
  decoder: LiveParamsDecoder[A, Params]
): LiveRouteParamsBuilder[
  R,
  A,
  Need,
  Ctx,
  Params,
  LiveRouteParamsCapability.DecodeOnly
] =
  LiveRouteParamsBuilder(
    pathCodec,
    decoder,
    None,
    mountPipeline,
    liveLayouts,
    rootLayout,
    hasRouteMountAspect
  )
```

Use `Any` for the seed's `R`, `Need`, and `Ctx` type arguments as current seed methods do.

On `LiveRouteParamsBuilder`, implement:

```scala
def mapParams[Params2](
  decodeParams: Params => Params2
)(
  encodeParams: Params2 => Params
)(using Capability =:= LiveRouteParamsCapability.Encodable)
  : LiveRouteParamsBuilder[
      R,
      A,
      Need,
      Ctx,
      Params2,
      LiveRouteParamsCapability.Encodable
    ] =
  LiveRouteParamsBuilder(
    pathCodec,
    paramsDecoder.mapDecodeOnly(decodeParams),
    paramsEncoder.map(baseEncode => params => baseEncode(encodeParams(params))),
    mountPipeline,
    liveLayouts,
    rootLayout,
    hasRouteMountAspect
  )

def mapParamsDecodeOnly[Params2](
  decodeParams: Params => Params2
): LiveRouteParamsBuilder[
  R,
  A,
  Need,
  Ctx,
  Params2,
  LiveRouteParamsCapability.DecodeOnly
] =
  LiveRouteParamsBuilder(
    pathCodec,
    paramsDecoder.mapDecodeOnly(decodeParams),
    None,
    mountPipeline,
    liveLayouts,
    rootLayout,
    hasRouteMountAspect
  )

def location(params: Params)(using Capability =:= LiveRouteParamsCapability.Encodable)
  : LiveLocation =
  locationEither(params).fold(
    error => throw new LiveLocation.EncodingException(error),
    identity
  )

def locationEither(params: Params)(
  using Capability =:= LiveRouteParamsCapability.Encodable
): Either[LiveLocation.EncodeError, LiveLocation] =
  paramsEncoder.get.apply(params).flatMap(LiveLocation.encode(pathCodec, _))
```

The `Option.get` is an internal invariant guarded by private constructors and `Capability =:= Encodable` evidence. Do not expose an `EncoderUnavailable` public error.

Add the no-argument variants when `Params =:= Unit`:

```scala
@targetName("unitParamsLocation")
def location(using
  Params =:= Unit,
  Capability =:= LiveRouteParamsCapability.Encodable
): LiveLocation =
  location(summon[Params =:= Unit].flip(()))

@targetName("unitParamsLocationEither")
def locationEither(using
  Params =:= Unit,
  Capability =:= LiveRouteParamsCapability.Encodable
): Either[LiveLocation.EncodeError, LiveLocation] =
  locationEither(summon[Params =:= Unit].flip(()))
```

- [ ] **Step 7: Migrate core params tests to the new contracts**

In `LiveRoutesLifecycleSpec.scala`, make the user mapping reversible:

```scala
.mapParams { case (userId, query) =>
  UserParams(userId, query.tab)
}(params => params.userId -> UserQuery(params.tab))
```

`SocketSpec.scala` already uses `LiveParamsDecoder.custom` from Task 1. Keep that test unchanged here.

- [ ] **Step 8: Run route and socket tests**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveLocationSpec scalive.LiveRoutesTypeSafetySpec scalive.LiveRoutesLifecycleSpec scalive.SocketSpec`

Expected: all four specs pass. The new type-safety test proves decode-only builders cannot construct locations.

---

### Task 3: Make Navigation APIs Safe By Default

**Files:**
- Modify: `scalive/src/scalive/Scalive.scala`
- Modify: `scalive/src/scalive/JS.scala`
- Modify: `scalive/src/scalive/LiveContext.scala`
- Modify: `scalive/src/scalive/LiveMountAspect.scala`
- Modify: `scalive/src/scalive/routing/LiveRoutesRuntime.scala`
- Create: `scalive/test/src/scalive/NavigationApiSpec.scala`
- Modify: `scalive/test/src/scalive/LiveMountAspectSpec.scala`
- Modify: `scalive/test/src/scalive/AsyncSpec.scala`
- Modify: `scalive/test/src/scalive/FlashSpec.scala`
- Modify: `scalive/test/src/scalive/LiveComponentParitySpec.scala`
- Modify: `scalive/test/src/scalive/LiveRoutesLifecycleSpec.scala`
- Modify: `scalive/test/src/scalive/SocketSpec.scala`

**Interfaces:**
- Consumes: `LiveLocation.href` and route-builder location methods.
- Produces: safe `LiveLocation` consumers and explicit `*Unsafe` string/URL escape hatches without changing runtime command payloads.

- [ ] **Step 1: Add failing link and JS tests**

Create `NavigationApiSpec.scala` with focused rendering and command assertions:

```scala
package scalive

import zio.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.test.*

object NavigationApiSpec extends ZIOSpecDefault:
  private val target = (live / "users" / PathCodec.int("id")).location(42)

  override def spec = suite("NavigationApiSpec")(
    test("typed links preserve Phoenix navigation attributes") {
      assertTrue(
        HtmlBuilder.build(link.navigate(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"redirect\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.patch(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"push\">User</a>",
        HtmlBuilder.build(link.patchReplace(target, "User")) ==
          "<a href=\"/users/42\" data-phx-link=\"patch\" data-phx-link-state=\"replace\">User</a>"
      )
    },
    test("typed JS commands preserve navigate and patch JSON") {
      import JSCommands.JSCommand.given

      assertTrue(
        JS.navigate(target).toJson == "[[\"navigate\",{\"href\":\"/users/42\"}]]",
        JS.patch(target, replace = true).toJson ==
          "[[\"patch\",{\"href\":\"/users/42\",\"replace\":true}]]"
      )
    },
    test("unsafe links and JS commands preserve raw destinations") {
      import JSCommands.JSCommand.given

      assertTrue(
        HtmlBuilder.build(link.patchUnsafe("?page=2", "Next")) ==
          "<a href=\"?page=2\" data-phx-link=\"patch\" data-phx-link-state=\"push\">Next</a>",
        JS.patchUnsafe("?page=2").toJson == "[[\"patch\",{\"href\":\"?page=2\"}]]"
      )
    }
  )
end NavigationApiSpec
```

- [ ] **Step 2: Add failing context and type-safety tests**

Add tests to the same suite that record internal commands and reject strings at compile time:

```scala
test("typed lifecycle navigation serializes locations once") {
  for
    commands <- Ref.make(List.empty[LiveNavigationCommand])
    runtime = new LiveNavigationRuntime:
      def request(command: LiveNavigationCommand) = commands.update(_ :+ command)
    ctx = LiveContext(staticChanged = false, navigation = runtime).messageContext[Unit, Unit]
    _      <- ctx.nav.pushNavigate(target)
    _      <- ctx.nav.replaceNavigate(target)
    _      <- ctx.nav.pushPatch(target)
    _      <- ctx.nav.replacePatch(target)
    _      <- ctx.nav.redirect(target)
    result <- commands.get
  yield assertTrue(
    result == List(
      LiveNavigationCommand.PushNavigate("/users/42"),
      LiveNavigationCommand.ReplaceNavigate("/users/42"),
      LiveNavigationCommand.PushPatch("/users/42"),
      LiveNavigationCommand.ReplacePatch("/users/42"),
      LiveNavigationCommand.Redirect("/users/42")
    )
  )
},
test("safe navigation APIs reject raw strings") {
  val linkErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    link.navigate("/users/42", "User")
  """)
  val jsErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    JS.patch("/users/42")
  """)
  val contextErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    def navigate(ctx: MessageContext[Unit, Unit]) = ctx.nav.pushNavigate("/users/42")
  """)
  val urlErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.http.URL
    link.navigate(URL.decode("/users/42").toOption.get, "User")
  """)

  assertTrue(
    linkErrors.nonEmpty,
    jsErrors.nonEmpty,
    contextErrors.nonEmpty,
    urlErrors.nonEmpty
  )
}
```

- [ ] **Step 3: Run the new navigation spec and confirm it fails**

Run: `mill --ticker false scalive.test.testOnly scalive.NavigationApiSpec`

Expected: compilation fails because safe methods still accept strings and unsafe methods do not exist.

- [ ] **Step 4: Replace link helpers with safe and unsafe variants**

In `Scalive.scala`:

```scala
object link:
  def navigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
    navigateUnsafe(to.href, mods*)

  def navigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

  def patch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
    patchUnsafe(to.href, mods*)

  def patchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    a(href := path, phx.link := "patch", phx.linkState := "push", mods)

  def patchReplace[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
    patchReplaceUnsafe(to.href, mods*)

  def patchReplaceUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
    a(href := path, phx.link := "patch", phx.linkState := "replace", mods)
```

- [ ] **Step 5: Replace JS navigation methods with safe and unsafe variants**

In the `JSCommand` extension in `JS.scala`:

```scala
def navigate(to: LiveLocation, replace: Boolean = false) =
  navigateUnsafe(to.href, replace)

def navigateUnsafe(href: String, replace: Boolean = false) =
  ops.addOp("navigate", Args.Href(href, Option.when(replace)(replace)))

def patch(to: LiveLocation, replace: Boolean = false) =
  patchUnsafe(to.href, replace)

def patchUnsafe(href: String, replace: Boolean = false) =
  ops.addOp("patch", Args.Href(href, Option.when(replace)(replace)))
```

- [ ] **Step 6: Replace lifecycle navigation methods with safe and unsafe variants**

In `LiveContext.scala`, make safe methods concrete delegates and raw methods explicit:

```scala
trait MountNavigation:
  def pushNavigate(to: LiveLocation): LiveIO[Unit] = pushNavigateUnsafe(to.href)
  def pushNavigateUnsafe(to: String): LiveIO[Unit]

  def replaceNavigate(to: LiveLocation): LiveIO[Unit] = replaceNavigateUnsafe(to.href)
  def replaceNavigateUnsafe(to: String): LiveIO[Unit]

  def redirect(to: LiveLocation): LiveIO[Unit] = redirectUnsafe(to.href)
  def redirectUnsafe(to: String): LiveIO[Unit]

trait Navigation extends MountNavigation:
  def pushPatch(to: LiveLocation): LiveIO[Unit] = pushPatchUnsafe(to.href)
  def pushPatchUnsafe(to: String): LiveIO[Unit]

  def replacePatch(to: LiveLocation): LiveIO[Unit] = replacePatchUnsafe(to.href)
  def replacePatchUnsafe(to: String): LiveIO[Unit]
```

Rename the five runtime implementation methods to their unsafe names. Keep all `LiveNavigationCommand` cases string-based so socket and HTTP behavior do not change.

- [ ] **Step 7: Split typed and unsafe mount-failure redirects**

In `LiveMountAspect.scala`:

```scala
enum LiveMountFailure:
  case Redirect(to: LiveLocation)
  case RedirectUnsafe(to: URL)
  case Unauthorized(reason: Option[String])
  case Stale(reason: Option[String])

object LiveMountFailure:
  def redirect(to: LiveLocation): LiveMountFailure = Redirect(to)
  def redirectUnsafe(to: URL): LiveMountFailure = RedirectUnsafe(to)
```

In `LiveRoutesRuntime.mountFailureReply`, serialize both variants:

```scala
case LiveMountFailure.Redirect(to) =>
  ZIO.succeed(redirectMessage(message, to.href))
case LiveMountFailure.RedirectUnsafe(to) =>
  ZIO.succeed(redirectMessage(message, to.encode))

private def redirectMessage(message: WebSocketMessage, href: String): WebSocketMessage =
  WebSocketMessage(
    message.joinRef,
    message.messageRef,
    message.topic,
    Protocol.EventRedirect,
    Payload.Redirect(href, None)
  )
```

Keep `redirectMessage` private to `LiveRoutesRuntime`; its payload is byte-for-byte equivalent to the current redirect branch.

- [ ] **Step 8: Migrate core tests to explicit unsafe calls**

Raw destinations in existing behavior tests are intentional test fixtures, so rename them without changing strings:

```text
ctx.nav.pushNavigate(raw)    -> ctx.nav.pushNavigateUnsafe(raw)
ctx.nav.replaceNavigate(raw) -> ctx.nav.replaceNavigateUnsafe(raw)
ctx.nav.pushPatch(raw)       -> ctx.nav.pushPatchUnsafe(raw)
ctx.nav.replacePatch(raw)    -> ctx.nav.replacePatchUnsafe(raw)
ctx.nav.redirect(raw)        -> ctx.nav.redirectUnsafe(raw)
```

Apply those exact replacements in `AsyncSpec.scala`, `FlashSpec.scala`, `LiveComponentParitySpec.scala`, `LiveRoutesLifecycleSpec.scala`, and `SocketSpec.scala`. Preserve query-only strings such as `"?q=1"` and `"?loop=true"` verbatim.

In `LiveMountAspectSpec.scala`, rename the existing arbitrary URL test call:

```scala
ZIO.fail(LiveMountFailure.redirectUnsafe(redirectUrl))
```

Add a second mount-aspect redirect test using:

```scala
val login = (live / "login").location
ZIO.fail(LiveMountFailure.redirect(login))
```

Assert the same `Payload.Redirect("/login", None)` result.

- [ ] **Step 9: Run all native library tests**

Run: `mill --ticker false scalive.test`

Expected: all native tests pass, including `NavigationApiSpec`; no existing wire-payload assertion changes.

---

### Task 4: Migrate The Beginner Example

**Files:**
- Create: `example/src/ExampleRoutes.scala`
- Modify: `example/src/Example.scala`
- Modify: `example/src/HomeLiveView.scala`

**Interfaces:**
- Consumes: named route builders and typed link helpers.
- Produces: a small recommended example with no duplicated internal paths.

- [ ] **Step 1: Create named example route builders**

Create `ExampleRoutes.scala`:

```scala
import scalive.*

object ExampleRoutes:
  val home    = live
  val counter = live / "counter"
  val list    = (live / "list").query[ListLiveView.ListParams]
  val todo    = live / "todo"
```

- [ ] **Step 2: Mount the named builders**

Replace the inline route declarations in `Example.liveRoutes`:

```scala
def liveRoutes(assets: StaticAssets) =
  (Live.router @@ RootLayout(assets))(
    ExampleRoutes.home    -> HomeLiveView(),
    ExampleRoutes.counter -> CounterLiveView(),
    ExampleRoutes.list    -> ListLiveView(),
    ExampleRoutes.todo    -> TodoLiveView()
  )
```

- [ ] **Step 3: Render typed destinations in `HomeLiveView`**

Replace `List[(String, String)]` with reusable locations:

```scala
val links = List(
  ExampleRoutes.counter.location                         -> "Counter",
  ExampleRoutes.list.location(ListLiveView.ListParams(Some("test"))) -> "List",
  ExampleRoutes.todo.location                            -> "Todo"
)

def render(model: Unit) =
  ul(
    cls := "mx-auto menu bg-base-100 rounded-box shadow-xl w-56",
    links.map((location, name) => li(link.navigate(location, name)))
  )
```

Run the formatter after implementation to align the long tuple line rather than hand-aligning it.

- [ ] **Step 4: Compile the example**

Run: `mill --ticker false example.compile`

Expected: compilation succeeds and `HomeLiveView` has no raw internal route strings.

---

### Task 5: Migrate E2E Routes And Navigation Callers

**Files:**
- Create: `e2eApp/src/E2ERoutes.scala`
- Modify: `e2eApp/src/E2EApp.scala`
- Modify: `e2eApp/src/NavigationLayout.scala`
- Modify: `e2eApp/src/NavigationLiveViews.scala`
- Modify: `e2eApp/src/KeyedComprehensionLiveView.scala`
- Modify: `e2eApp/src/ComponentsLiveView.scala`
- Modify: `e2eApp/src/PortalLiveView.scala`
- Modify: `e2eApp/src/StreamLiveView.scala`
- Modify: `e2eApp/src/FormLiveViews.scala`
- Modify: `e2eApp/src/ErrorLiveView.scala`
- Modify: `e2eApp/src/IssueLiveViews.scala`

**Interfaces:**
- Consumes: route-builder locations, bidirectional/decode-only params APIs, and safe/unsafe navigation consumers.
- Produces: shared E2E declarations for full internal destinations while preserving explicit raw fixture behavior.

- [ ] **Step 1: Add shared route builders for outbound fixture destinations**

Create `E2ERoutes.scala`. Keep only routes reused by outbound callers here; unrelated E2E routes remain inline in `E2EApp`:

```scala
import zio.http.codec.PathCodec

import scalive.*

object E2ERoutes:
  val keyedComprehension =
    (live / "keyed-comprehension").query[KeyedComprehensionLiveView.UrlParams]

  val navigationA =
    (live / "navigation" / "a").query[NavigationLiveViews.AParams]

  private val navigationBParams =
    (live / "navigation" / "b").queryOptional[String]("container")

  val navigationB =
    navigationBParams.mapParams(
      container => NavigationLiveViews.BParams(container.contains("1"))
    )(
      params => Option.when(params.withContainerRequested)("1")
    )

  val navigationBItemLocation =
    (live / "navigation" / "b" / PathCodec.string("id"))
      .queryOptional[String]("container")

  val navigationBItemRoute =
    navigationBItemLocation.mapParamsDecodeOnly { case (id, container) =>
      NavigationLiveViews.BParams(container.contains("1"), Some(id))
    }

  val navigationRedirectLoop =
    (live / "navigation" / "redirectloop").query[NavigationLiveViews.RedirectLoopParams]

  val stream = (live / "stream").queryOptional[String]("empty_item")
  val healthy = (live / "healthy" / PathCodec.string("category")).params
  val components = (live / "components").query[ComponentsLiveView.UrlParams]
  val portal = (live / "portal").query[PortalLiveView.QueryParams]

  val formLocation = live / "form"
  val form = formLocation.paramsDecodeOnly(FormQueryParams.decoder)

  val issue3047A = live / "issues" / "3047" / "a"
  val issue3047B = live / "issues" / "3047" / "b"
  val issue3194Other = live / "issues" / "3194" / "other"
  val issue3200 = (live / "issues" / "3200" / PathCodec.string("tab")).params
  val issue3496B = live / "issues" / "3496" / "b"
  val issue3529 = (live / "issues" / "3529").queryOptional[String]("param")
  val issue3530 = (live / "issues" / "3530").queryOptional[String]("q")
  val issue3612A = live / "issues" / "3612" / "a"
  val issue3612B = live / "issues" / "3612" / "b"
  val issue3681 = live / "issues" / "3681"
  val issue3681Away = live / "issues" / "3681" / "away"
  val issue3686A = live / "issues" / "3686" / "a"
  val issue3686B = live / "issues" / "3686" / "b"
  val issue3686C = live / "issues" / "3686" / "c"
  val issue3709 = live / "issues" / "3709"
  val issue3709Id = live / "issues" / "3709" / PathCodec.int("id")
  val issue4094 = (live / "issues" / "4094").queryOptional[String]("foo")
end E2ERoutes
```

- [ ] **Step 2: Mount the shared builders and mark irreversible params explicitly**

In `E2EApp.liveRoutes`, replace the matching inline declarations with `E2ERoutes` values. The significant route group becomes:

```scala
E2ERoutes.keyedComprehension -> KeyedComprehensionLiveView(assets),
Live.session("navigation")(
  E2ERoutes.navigationA            -> NavigationALiveView(),
  E2ERoutes.navigationB            -> NavigationBLiveView(),
  E2ERoutes.navigationBItemRoute   -> NavigationBLiveView(),
E2ERoutes.navigationRedirectLoop -> RedirectLoopLiveView()
),
E2ERoutes.stream     -> StreamLiveView(),
E2ERoutes.healthy { (category, _, _) => HealthyLiveView(category) },
E2ERoutes.components -> ComponentsLiveView(),
E2ERoutes.form       -> FormLiveView(),
E2ERoutes.portal     -> PortalLiveView()
```

Replace the matching inline issue builders at their existing positions while preserving their current LiveViews and session/layout grouping:

| Current `E2EApp.scala` route | Shared builder |
|---|---|
| `live / "issues" / "3047" / "a"` | `E2ERoutes.issue3047A` |
| `live / "issues" / "3047" / "b"` | `E2ERoutes.issue3047B` |
| `live / "issues" / "3194" / "other"` | `E2ERoutes.issue3194Other` |
| `(live / "issues" / "3200" / PathCodec.string("tab")).params` | `E2ERoutes.issue3200` |
| `live / "issues" / "3496" / "b"` | `E2ERoutes.issue3496B` |
| `(live / "issues" / "3529").queryOptional[String]("param")` | `E2ERoutes.issue3529` |
| `(live / "issues" / "3530").queryOptional[String]("q")` | `E2ERoutes.issue3530` |
| `live / "issues" / "3612" / "a"` | `E2ERoutes.issue3612A` |
| `live / "issues" / "3612" / "b"` | `E2ERoutes.issue3612B` |
| `live / "issues" / "3681"` | `E2ERoutes.issue3681` |
| `live / "issues" / "3681" / "away"` | `E2ERoutes.issue3681Away` |
| `live / "issues" / "3686" / "a"` | `E2ERoutes.issue3686A` |
| `live / "issues" / "3686" / "b"` | `E2ERoutes.issue3686B` |
| `live / "issues" / "3686" / "c"` | `E2ERoutes.issue3686C` |
| `(live / "issues" / "4094").queryOptional[String]("foo")` | `E2ERoutes.issue4094` |

Migrate the remaining one-way mappings exactly:

```scala
E2ERoutes.issue3709.params
  .mapParamsDecodeOnly(_ => Option.empty[String])

E2ERoutes.issue3709Id.params
  .mapParamsDecodeOnly(id => Option(id.toString))

(live / "issues" / "4027")
  .queryOptional[String]("case")
  .mapParams(caseName => Issue4027LiveView.QueryParams(caseName.getOrElse("first")))(
    params => Some(params.caseName)
  )

(live / "issues" / "4066")
  .queryOptional[Int]("delay")
  .mapParams(delay => Issue4066LiveView.QueryParams(delay.getOrElse(3000)))(
    params => Some(params.delay)
  )
```

Rename `FormQueryParams.codec` and `ErrorLiveView.QueryParams.codec` to `decoder`, change their type to `LiveParamsDecoder`, and construct them with `LiveParamsDecoder.custom`. Use these exact route declarations:

```scala
E2ERoutes.form -> FormLiveView(),
(live / "form" / "nested").paramsDecodeOnly(FormQueryParams.decoder) -> NestedFormLiveView(),
(live / "form" / "dynamic-inputs").paramsDecodeOnly(FormQueryParams.decoder) ->
  FormDynamicInputsLiveView(),
(live / "errors").paramsDecodeOnly(ErrorLiveView.QueryParams.decoder) -> ErrorLiveView()
```

- [ ] **Step 3: Generate typed locations in navigation-focused fixtures**

Replace only the destination argument at each listed call site, preserving its existing modifiers and content:

| File and current line | Method | New destination |
|---|---|---|
| `NavigationLayout.scala:24` | `link.navigate` | `E2ERoutes.navigationA.location(NavigationLiveViews.AParams(None))` |
| `NavigationLayout.scala:29` | `link.navigate` | `E2ERoutes.navigationB.location(NavigationLiveViews.BParams(false))` |
| `NavigationLayout.scala:34` | `link.navigate` | `E2ERoutes.stream.location(None)` |
| `NavigationLayout.scala:39` | rename to `link.navigateUnsafe` | Keep `"/navigation/dead"` |
| `NavigationLiveViews.scala:26` | `link.patch` | `E2ERoutes.navigationA.location(AParams(Some(model.paramNext)))` |
| `NavigationLiveViews.scala:31` | `link.patchReplace` | `E2ERoutes.navigationA.location(AParams(Some(model.paramNext)))` |
| `NavigationLiveViews.scala:36` | `link.navigate` | `E2ERoutes.navigationB.location(BParams(false)).withFragment("items-item-42")` |
| `NavigationLiveViews.scala:86` | `link.patch` | `E2ERoutes.navigationBItemLocation.location(item.id -> Option.when(model.withContainer)("1"))` |
| `NavigationLiveViews.scala:125` | rename to `ctx.nav.pushPatchUnsafe` | Keep `"?loop=true"` |
| `NavigationLiveViews.scala:139` | rename to `link.patchUnsafe` | Keep `"?loop=true"` |
| `KeyedComprehensionLiveView.scala:51` | `link.patch` | `E2ERoutes.keyedComprehension.location(UrlParams(Some("all_keyed")))` |
| `KeyedComprehensionLiveView.scala:56` | `link.patch` | `E2ERoutes.keyedComprehension.location(UrlParams(Some("rows_keyed")))` |
| `KeyedComprehensionLiveView.scala:61` | `link.patch` | `E2ERoutes.keyedComprehension.location(UrlParams(Some("no_keyed")))` |
| `StreamLiveView.scala:402` | `link.patch` | `E2ERoutes.healthy.location(otherCategory(model.category))` |

In `ComponentsLiveView.render`, construct the location once before the root `div`:

```scala
val focusWrap = E2ERoutes.components.location(UrlParams(Some("focus_wrap")))
```

Then replace `paramsHref(UrlParams(Some("focus_wrap")))` with `focusWrap.href` and replace `JS.patch(paramsHref(UrlParams(Some("focus_wrap"))))` with `JS.patch(focusWrap)`.

Remove `NavigationBLiveView.itemHref` and `ComponentsLiveView.paramsHref`; the named route builders replace both manual serializers.

- [ ] **Step 4: Generate typed locations in portal, form, and issue fixtures**

Replace only the destination argument at these full-location call sites:

| File and current line | Method | New destination |
|---|---|---|
| `PortalLiveView.scala:48` | `JS.patch` | `E2ERoutes.portal.location(PortalLiveView.QueryParams(Some((model.count + 1).toString)))` |
| `PortalLiveView.scala:56` | `JS.navigate` | `E2ERoutes.formLocation.location` |
| `PortalLiveView.scala:112` | `JS.patch` | `E2ERoutes.portal.location(PortalLiveView.QueryParams(Some((count + 1).toString)))` |
| `IssueLiveViews.scala:340` | `link.navigate` | `E2ERoutes.issue3047A.location` |
| `IssueLiveViews.scala:341` | `link.navigate` | `E2ERoutes.issue3047B.location` |
| `IssueLiveViews.scala:399` | `link.navigate` | `E2ERoutes.issue3529.location(Some(model.next))` |
| `IssueLiveViews.scala:400` | `link.patch` | `E2ERoutes.issue3529.location(Some(model.next))` |
| `IssueLiveViews.scala:453` | `link.patch` | `E2ERoutes.issue3530.location(Some("a"))` |
| `IssueLiveViews.scala:454` | `link.patch` | `E2ERoutes.issue3530.location(Some("b"))` |
| `IssueLiveViews.scala:823` | `ctx.nav.pushNavigate` | `E2ERoutes.issue3194Other.location` |
| `IssueLiveViews.scala:867` | `JS.patch` | `E2ERoutes.issue3200.location("messages")` |
| `IssueLiveViews.scala:868` | `JS.patch` | `E2ERoutes.issue3200.location("settings")` |
| `IssueLiveViews.scala:1242` | `link.navigate` | `E2ERoutes.issue3496B.location` |
| `IssueLiveViews.scala:1285` | `ctx.nav.pushNavigate` | `E2ERoutes.issue3612A.location` |
| `IssueLiveViews.scala:1286` | `ctx.nav.pushNavigate` | `E2ERoutes.issue3612B.location` |
| `IssueLiveViews.scala:1458` | `link.navigate` | `E2ERoutes.issue3681.location` |
| `IssueLiveViews.scala:1471` | `link.navigate` | `E2ERoutes.issue3681Away.location` |
| `IssueLiveViews.scala:1562` | `ctx.nav.pushNavigate` | `E2ERoutes.issue3686B.location` |
| `IssueLiveViews.scala:1564` | `ctx.nav.redirect` | `E2ERoutes.issue3686C.location` |
| `IssueLiveViews.scala:1566` | `ctx.nav.pushNavigate` | `E2ERoutes.issue3686A.location` |
| `IssueLiveViews.scala:1599` | `link.patch` | `E2ERoutes.issue3709Id.location(i)` |
| `IssueLiveViews.scala:2166` | `ctx.nav.redirect` | `E2ERoutes.navigationA.location(NavigationLiveViews.AParams(None))` |
| `IssueLiveViews.scala:2173` | `link.patch` | `E2ERoutes.issue4094.location(Some("bar"))` |

Rename these exceptional calls while keeping their existing strings:

| File and current line | Unsafe method | Reason |
|---|---|---|
| `FormLiveViews.scala:59` | `ctx.nav.pushPatchUnsafe` | Custom decoder query not represented by an outbound codec |
| `FormLiveViews.scala:88` | `ctx.nav.pushPatchUnsafe` | Custom decoder query not represented by an outbound codec |
| `FormLiveViews.scala:266` | `ctx.nav.pushPatchUnsafe` | Custom decoder query not represented by an outbound codec |
| `IssueLiveViews.scala:1040` | `link.navigateUnsafe` | Undeclared `?nav` query flag |
| `IssueLiveViews.scala:1381` | `link.navigateUnsafe` | Undeclared `navigated` query parameter |
| `IssueLiveViews.scala:1433` | `link.navigateUnsafe` | Undeclared `navigated` query parameter |

- [ ] **Step 5: Verify no old raw method call remains**

Run:

```bash
rg -U 'link\.(navigate|patch|patchReplace)\(\s*s?"|JS\.(navigate|patch)\(\s*s?"|ctx\.nav\.(pushNavigate|replaceNavigate|pushPatch|replacePatch|redirect)\(\s*s?"' example e2eApp scalive/test/src
```

Expected: no matches. Explicit unsafe calls may match separate searches and are expected.

- [ ] **Step 6: Compile all Scala modules**

Run: `mill --ticker false scalive.compile scalive.test.compile example.compile e2eApp.compile`

Expected: all modules compile. Fix only migration/type inference errors; do not add string overloads or implicit conversions.

---

### Task 6: Refresh Public Documentation

**Files:**
- Modify: `doc/public-api-reference.md`
- Modify: `doc/user-facing-api-assessment.md`
- Modify: `doc/api-improvement-ideas.md`
- Modify: `doc/superpowers/specs/2026-07-15-typed-outbound-navigation-design.md`

**Interfaces:**
- Consumes: final compiled signatures from Tasks 1-5.
- Produces: documentation that consistently presents route-derived locations as the default and unsafe methods as explicit exceptions.

- [ ] **Step 1: Replace navigation signatures in the API reference**

Document the final safe and unsafe link, lifecycle, JS, and mount-failure methods. Replace stale codec-based JS examples with this pattern:

```scala
object Routes:
  final case class UserLocation(id: Int, tab: Option[String])

  val user =
    (live / "users" / PathCodec.int("id"))
      .queryOptional[String]("tab")
      .mapParams { case (id, tab) => UserLocation(id, tab) }(
        location => location.id -> location.tab
      )

val settings = Routes.user.location(UserLocation(42, Some("settings")))

link.navigate(settings, "Settings")
ctx.nav.pushNavigate(settings)
JS.patch(settings)
```

Add `LiveLocation`, `locationEither`, `withFragmentEither`, `LiveParamsDecoder`, `paramsDecodeOnly`, and `mapParamsDecodeOnly` to their relevant sections. State that direct methods throw `EncodingException` only for codec/domain invariant violations.

- [ ] **Step 2: Update the API assessment finding**

Replace the current high finding with an addressed entry that records:

```markdown
### Addressed - API Design - Outbound navigation derives from inbound routes

Named route builders now encode `LiveLocation` values from the same path and query codecs used for inbound matching. Safe link, lifecycle, JS, and redirect APIs require those locations; raw destinations are explicit unsafe escape hatches.

Remaining boundary: the API does not prove current-view patch validity or live-session membership, and typed query-only patches remain out of scope.
```

Update the executive summary, risk register, and confidence notes so they no longer claim outbound navigation is generally string-only.

- [ ] **Step 3: Close the matching improvement idea and reconcile the design**

In `doc/api-improvement-ideas.md`, mark the typed route/location proposal implemented and link to the design spec. Remove obsolete suggestions to pass query codecs to full navigate/redirect methods.

Compare `doc/superpowers/specs/2026-07-15-typed-outbound-navigation-design.md` with compiled signatures. Correct only naming or implementation details that changed during implementation; do not expand scope.

- [ ] **Step 4: Search for stale navigation API claims**

Run:

```bash
rg 'JS\.patch\(codec|link\.patch\(codec|pushNavigate\(to: String|pushPatch\(to: String|link\.navigate\(path: String' README.md doc
```

Expected: no stale current-API signatures. Historical text must be clearly labeled historical if retained.

---

### Task 7: Format And Verify End To End

**Files:**
- Modify only files changed by the formatter or required to fix verified failures.
- Review: every path reported by `git status --short`.

**Interfaces:**
- Consumes: all implementation, migration, and documentation tasks.
- Produces: formatted, fully tested, reviewable typed outbound navigation change.

- [ ] **Step 1: Run formatting and Scalafix**

Run: `mill --ticker false __.reformat + __.fix`

Expected: command succeeds. Inspect any semantic Scalafix changes before continuing.

- [ ] **Step 2: Run focused typed-navigation tests after formatting**

Run:

```bash
mill --ticker false scalive.test.testOnly \
  scalive.LiveLocationSpec \
  scalive.NavigationApiSpec \
  scalive.LiveRoutesTypeSafetySpec \
  scalive.LiveRoutesLifecycleSpec \
  scalive.LiveMountAspectSpec \
  scalive.SocketSpec
```

Expected: all selected specs pass.

- [ ] **Step 3: Run all native tests**

Run: `mill --ticker false __.test`

Expected: all module tests pass.

- [ ] **Step 4: Run the upstream browser parity suite**

Run: `./scripts/e2e-run-upstream.sh`

Expected: all synchronized Phoenix LiveView navigation and fixture tests pass. Any failure must be diagnosed before changing behavior; do not weaken typed APIs to mask an E2E fixture migration error.

- [ ] **Step 5: Verify safe/unsafe API boundaries**

Run:

```bash
rg 'def (navigate|patch|patchReplace|pushNavigate|replaceNavigate|pushPatch|replacePatch|redirect)\([^)]*String' scalive/src/scalive
rg -U 'link\.(navigate|patch|patchReplace)\(\s*s?"|JS\.(navigate|patch)\(\s*s?"|ctx\.nav\.(pushNavigate|replaceNavigate|pushPatch|replacePatch|redirect)\(\s*s?"' example e2eApp scalive/test/src
```

Expected: both commands find no raw-string safe definitions or call sites. Explicit unsafe methods do not match because their names end in `Unsafe`.

- [ ] **Step 6: Review the final diff**

Run: `git status --short`, `git diff --check`, and `git diff --stat` as separate commands.

Expected: only intended source, test, example, E2E, and documentation files are changed; `git diff --check` prints no errors; no generated assets, browser logs, or secrets are present.
