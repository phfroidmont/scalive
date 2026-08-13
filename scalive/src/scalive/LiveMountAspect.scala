package scalive

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Input available to a [[LiveMountAspect]] callback.
  *
  * During disconnected mount, `request` is the browser's HTTP request. During connected mount,
  * Scalive synthesizes it from the URL in the socket join, so it does not retain the original
  * request's cookies, headers, method, or body. Transfer only the minimum data needed by connected
  * mount through the aspect's signed claims, and revalidate mutable authorization state there.
  *
  * @param params
  *   the route's decoded path parameters
  * @param request
  *   the phase-specific HTTP request
  */
final case class LiveMountRequest[+A](params: A, request: Request):
  /** The request URL for this mount phase. */
  def url: URL = request.url

/** Stops the connected [[LiveMountAspect]] phase.
  *
  * Redirect failures become socket redirect events. Unauthorized and stale failures become the
  * corresponding Phoenix-compatible join errors; their optional reasons are logged server-side and
  * are not sent to the client.
  */
enum LiveMountFailure:
  /** Redirects to a location produced by a typed Live route. */
  case Redirect(to: LiveLocation)

  /** Redirects to an unchecked URL.
    *
    * No same-origin or local-path validation is performed. Validate any untrusted input before
    * constructing this failure.
    */
  case RedirectUnsafe(to: URL)

  /** Rejects the socket join as unauthorized. */
  case Unauthorized(reason: Option[String])

  /** Rejects the socket join as stale, indicating that the client should reload. */
  case Stale(reason: Option[String])

/** Constructors for connected mount failures. */
object LiveMountFailure:
  /** Redirects connected mount to a typed Live route location. */
  def redirect(to: LiveLocation): LiveMountFailure =
    LiveMountFailure.Redirect(to)

  /** Redirects connected mount to an unchecked URL.
    *
    * This is an escape hatch for destinations that cannot be represented by [[LiveLocation]]. It
    * does not validate origin or locality and can create an open redirect when given untrusted
    * input.
    */
  def redirectUnsafe(to: URL): LiveMountFailure =
    LiveMountFailure.RedirectUnsafe(to)

  /** Rejects connected mount as unauthorized without logging an application reason. */
  def unauthorized: LiveMountFailure =
    LiveMountFailure.Unauthorized(None)

  /** Rejects connected mount as unauthorized and logs `reason` server-side.
    *
    * The reason is not included in the socket reply.
    */
  def unauthorized(reason: String): LiveMountFailure =
    LiveMountFailure.Unauthorized(Some(reason))

  /** Rejects connected mount as stale without logging an application reason. */
  def stale: LiveMountFailure =
    LiveMountFailure.Stale(None)

  /** Rejects connected mount as stale and logs `reason` server-side.
    *
    * The reason is not included in the socket reply.
    */
  def stale(reason: String): LiveMountFailure =
    LiveMountFailure.Stale(Some(reason))
end LiveMountFailure

/** Produces context immediately before both disconnected and connected LiveView mount.
  *
  * The disconnected callback runs before the initial HTTP LiveView is built and mounted. Its
  * `Claims` are JSON-encoded into the signed root LiveView session token, then decoded for the
  * connected callback before a fresh connected LiveView is built and mounted. The two `Ctx` values
  * are computed independently; only `Claims` cross the phase boundary.
  *
  * Claims are authenticated by the session signature but are not encrypted. They are visible to the
  * client and must not contain passwords, session cookie values, private access tokens, or other
  * secrets. Prefer a minimal public identifier, and revalidate revocation and authorization in
  * connected mount. Claim encoding and transport are framework internals, not a stable wire format.
  *
  * A disconnected failure is the `Response` returned for the HTTP request. A connected failure is
  * mapped from [[LiveMountFailure]] to the socket reply. JSON encoding failure produces an HTTP
  * 500; missing or undecodable claims reject connected mount as unauthorized.
  *
  * @tparam R
  *   the ZIO environment required by the callbacks
  * @tparam A
  *   the route's decoded path-parameter type
  * @tparam In
  *   context supplied by the preceding aspect, or `Any` for the first aspect
  * @tparam Claims
  *   JSON-serializable data transferred from disconnected to connected mount
  * @tparam Ctx
  *   context produced independently for each mount phase
  */
final case class LiveMountAspect[R, A, -In, Claims, Ctx] private[scalive] (
  private[scalive] val disconnected: (LiveMountRequest[A], In) => ZIO[R, Response, (Claims, Ctx)],
  private[scalive] val connected: (Claims, LiveMountRequest[A], In) => ZIO[R, LiveMountFailure, Ctx]
)(using private[scalive] val claimsCodec: JsonCodec[Claims]):

  /** Transforms this aspect's context in both mount phases without changing its claims or input. */
  def map[Ctx2](f: Ctx => Ctx2): LiveMountAspect[R, A, In, Claims, Ctx2] =
    LiveMountAspect(
      (request, input) =>
        disconnected(request, input).map { case (claims, ctx) => claims -> f(ctx) },
      (claims, request, input) => connected(claims, request, input).map(f)
    )

  /** Composes two aspects from left to right in both mount phases.
    *
    * `that` runs only after this aspect succeeds and receives this aspect's phase-specific `Ctx` as
    * its input. Both claim values cross the disconnected-to-connected boundary. The resulting
    * context is selected by [[ContextAppend]]: the built-in instances discard an initial `Any`
    * identity context and otherwise produce `(Ctx, Ctx2)`. Existing tuples are not recursively
    * flattened; use [[map]] or a custom `ContextAppend` when another shape is required.
    *
    * @param that
    *   the aspect to run after this one
    */
  def ++[R1, Claims2, Ctx2, Result](
    that: LiveMountAspect[R1, A, Ctx, Claims2, Ctx2]
  )(using
    JsonCodec[(Claims, Claims2)],
    ContextAppend.Aux[Ctx, Ctx2, Result]
  ): LiveMountAspect[R & R1, A, In, (Claims, Claims2), Result] =
    val append = summon[ContextAppend.Aux[Ctx, Ctx2, Result]]
    LiveMountAspect(
      (request, input) =>
        for
          left  <- disconnected(request, input)
          right <- that.disconnected(request, left._2)
        yield (left._1 -> right._1) -> append.append(left._2, right._2),
      (claims, request, input) =>
        for
          left  <- connected(claims._1, request, input)
          right <- that.connected(claims._2, request, left)
        yield append.append(left, right)
    )

  private[scalive] def runtime: LiveMountAspectRuntime[R, A, In, Ctx] =
    val codec = claimsCodec
    new LiveMountAspectRuntime[R, A, In, Ctx]:
      def runDisconnected(request: LiveMountRequest[A], input: In): ZIO[R, Response, (Json, Ctx)] =
        disconnected(request, input).flatMap { case (claims, ctx) =>
          given JsonCodec[Claims] = codec
          ZIO
            .fromEither(claims.toJsonAST)
            .mapError(error =>
              Response
                .text(s"Could not encode LiveMountAspect claims: $error")
                .status(Status.InternalServerError)
            )
            .map(_ -> ctx)
        }

      def runConnected(
        claims: Option[Json],
        request: LiveMountRequest[A],
        input: In
      ): ZIO[R, LiveMountFailure, Ctx] =
        claims match
          case Some(value) =>
            given JsonCodec[Claims] = codec
            ZIO
              .fromEither(value.as[Claims])
              .mapError(error =>
                LiveMountFailure.unauthorized(s"Could not decode LiveMountAspect claims: $error")
              )
              .flatMap(connected(_, request, input))
          case None =>
            ZIO.fail(LiveMountFailure.unauthorized("Missing LiveMountAspect claims"))
    end new
  end runtime

end LiveMountAspect

/** Constructors for mount aspects. */
object LiveMountAspect:
  /** Creates an aspect that consumes context from a preceding aspect.
    *
    * `disconnected` runs before HTTP mount and either returns claims plus disconnected context or
    * fails with the HTTP response to return. `connected` receives the verified claims and a
    * phase-specific request before socket mount, and either returns fresh connected context or a
    * [[LiveMountFailure]]. The connected request is synthesized from the socket join URL and does
    * not preserve the original HTTP request's cookies or headers.
    *
    * @param disconnected
    *   the disconnected-mount callback
    * @param connected
    *   the connected-mount callback
    */
  def make[R, A, In, Claims: JsonCodec, Ctx](
    disconnected: (LiveMountRequest[A], In) => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveMountRequest[A], In) => ZIO[R, LiveMountFailure, Ctx]
  ): LiveMountAspect[R, A, In, Claims, Ctx] =
    LiveMountAspect(disconnected, connected)

  /** Creates an aspect that does not consume context from a preceding aspect.
    *
    * This is the request-only form of [[make]]. The callbacks have the same lifecycle, claim, and
    * failure behavior, while the aspect input is fixed to `Any` and ignored.
    *
    * @param disconnected
    *   the disconnected-mount callback
    * @param connected
    *   the connected-mount callback
    */
  def fromRequest[R, A, Claims: JsonCodec, Ctx](
    disconnected: LiveMountRequest[A] => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveMountRequest[A]) => ZIO[R, LiveMountFailure, Ctx]
  ): LiveMountAspect[R, A, Any, Claims, Ctx] =
    LiveMountAspect(
      (request, _) => disconnected(request),
      (claims, request, _) => connected(claims, request)
    )

  /** Authenticates a named cookie for HTTP mount and resumes minimal claims for socket mount.
    *
    * During disconnected mount, this helper reads `cookieName` from the browser request and passes
    * its content to `authenticate`. A missing cookie or `None` result returns an HTTP 303 redirect
    * to `onUnauthenticated`. The helper does not validate the cookie itself; `authenticate` owns
    * its authenticity, expiry, and revocation policy.
    *
    * On success, the returned claims are embedded in the signed LiveView session and the returned
    * context is used for disconnected mount. During connected mount, where the synthesized request
    * does not retain the browser cookie, `resume` receives those verified claims and must reload or
    * revalidate current server state. A `None` result emits a socket redirect to
    * `onUnauthenticated`.
    *
    * Claims are signed but not encrypted and are visible to the client. Do not put the cookie value
    * or any other secret in them; use the smallest non-sensitive identifier needed by `resume`.
    *
    * @param cookieName
    *   the request-cookie name to authenticate
    * @param onUnauthenticated
    *   the typed location used for both HTTP and socket redirects
    * @param authenticate
    *   validates the cookie and returns transferable claims plus disconnected context
    * @param resume
    *   revalidates claims and returns fresh connected context
    */
  def authenticated[R, Claims: JsonCodec, Ctx](
    cookieName: String,
    onUnauthenticated: LiveLocation
  )(
    authenticate: String => URIO[R, Option[(Claims, Ctx)]],
    resume: Claims => URIO[R, Option[Ctx]]
  ): LiveMountAspect[R, Any, Any, Claims, Ctx] =
    fromRequest[R, Any, Claims, Ctx](
      request =>
        request.request.cookie(cookieName) match
          case Some(cookie) =>
            authenticate(cookie.content).flatMap {
              case Some(result) => ZIO.succeed(result)
              case None         => ZIO.fail(Response.seeOther(onUnauthenticated.url))
            }
          case None =>
            ZIO.fail(Response.seeOther(onUnauthenticated.url)),
      (claims, _) =>
        resume(claims).flatMap {
          case Some(context) => ZIO.succeed(context)
          case None          => ZIO.fail(LiveMountFailure.redirect(onUnauthenticated))
        }
    )

  final private[scalive] case class EmptyClaims() derives JsonCodec

  private[scalive] def identityPipeline[A, Ctx]: LiveMountPipeline[Any, A, Ctx, Ctx] =
    LiveMountPipeline.identity[A, Ctx]
end LiveMountAspect

/** Controls how mount-aspect contexts accumulate.
  *
  * This is an advanced extension point used by aspect and route-builder composition. The built-in
  * behavior discards the initial `Any` identity context and otherwise pairs contexts as
  * `(In, Out)`. It does not recursively flatten tuples. Custom instances may choose another
  * `Result`, but `left` must recover the original `In` from an appended result so layouts installed
  * before a later aspect continue to receive their original context.
  *
  * @tparam In
  *   the context accumulated before the next aspect
  * @tparam Out
  *   the context produced by the next aspect
  */
trait ContextAppend[In, Out]:
  /** The accumulated context type. */
  type Result

  /** Combines the preceding and newly produced contexts. */
  def append(input: In, output: Out): Result

  /** Projects the preceding context from an accumulated result. */
  def left(result: Result): In

/** Built-in and refined forms of [[ContextAppend]]. */
object ContextAppend extends LowPriorityContextAppend:
  /** Refines [[ContextAppend.Result]] to `Result0`. */
  type Aux[In, Out, Result0] = ContextAppend[In, Out] { type Result = Result0 }

  /** Drops the initial `Any` identity context, making the first aspect's output the whole context.
    */
  given empty[Out]: ContextAppend[Any, Out] with
    type Result = Out
    def append(input: Any, output: Out): Out = output
    def left(result: Out): Any               = ()

private trait LowPriorityContextAppend:
  given tupled[In, Out]: ContextAppend[In, Out] with
    type Result = (In, Out)
    def append(input: In, output: Out): (In, Out) = input -> output
    def left(result: (In, Out)): In               = result._1

private[scalive] trait LiveMountAspectRuntime[R, A, -In, Ctx]:
  def runDisconnected(request: LiveMountRequest[A], input: In): ZIO[R, Response, (Json, Ctx)]
  def runConnected(claims: Option[Json], request: LiveMountRequest[A], input: In)
    : ZIO[R, LiveMountFailure, Ctx]

  def map[Ctx2](f: Ctx => Ctx2): LiveMountAspectRuntime[R, A, In, Ctx2] =
    val self = this
    new LiveMountAspectRuntime[R, A, In, Ctx2]:
      def runDisconnected(request: LiveMountRequest[A], input: In): ZIO[R, Response, (Json, Ctx2)] =
        self.runDisconnected(request, input).map { case (claims, ctx) => claims -> f(ctx) }

      def runConnected(
        claims: Option[Json],
        request: LiveMountRequest[A],
        input: In
      ): ZIO[R, LiveMountFailure, Ctx2] =
        self.runConnected(claims, request, input).map(f)

private[scalive] trait LiveMountPipeline[-R, A, -In, Ctx]:
  def runDisconnected(request: LiveMountRequest[A], input: In): ZIO[R, Response, (Json, Ctx)]
  def runConnected(claims: Option[Json], request: LiveMountRequest[A], input: In)
    : ZIO[R, LiveMountFailure, Ctx]

  def ++[R1, Ctx2, Result](
    that: LiveMountAspectRuntime[R1, A, Ctx, Ctx2]
  )(using append: ContextAppend.Aux[Ctx, Ctx2, Result]
  ): LiveMountPipeline[R & R1, A, In, Result] =
    val self = this
    new LiveMountPipeline[R & R1, A, In, Result]:
      def runDisconnected(request: LiveMountRequest[A], input: In) =
        for
          left  <- self.runDisconnected(request, input)
          right <- that.runDisconnected(request, left._2)
        yield Json.Arr(Chunk(left._1, right._1)) -> append.append(left._2, right._2)

      def runConnected(claims: Option[Json], request: LiveMountRequest[A], input: In) =
        claims match
          case Some(Json.Arr(values)) if values.length == 2 =>
            for
              left  <- self.runConnected(Some(values(0)), request, input)
              right <- that.runConnected(Some(values(1)), request, left)
            yield append.append(left, right)
          case _ =>
            ZIO.fail(LiveMountFailure.unauthorized("Invalid composed LiveMountAspect claims"))

private[scalive] object LiveMountPipeline:
  def identity[A, Ctx]: LiveMountPipeline[Any, A, Ctx, Ctx] =
    new LiveMountPipeline[Any, A, Ctx, Ctx]:
      def runDisconnected(request: LiveMountRequest[A], input: Ctx): UIO[(Json, Ctx)] =
        ZIO.succeed(Json.Obj() -> input)

      def runConnected(claims: Option[Json], request: LiveMountRequest[A], input: Ctx): UIO[Ctx] =
        ZIO.succeed(input)
