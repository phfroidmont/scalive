package scalive

import zio.http.{Request, Response, URL}
import zio.json.JsonCodec
import zio.{ZIO, ZIOAspect}

/** Input available to a [[LiveSessionMountAspect]] callback.
  *
  * During disconnected mount, `request` is the browser's HTTP request. During connected mount,
  * Scalive synthesizes it from the URL in the socket join, so it does not retain the original
  * request's cookies, headers, method, or body. Transfer only the minimum data needed by connected
  * mount through the aspect's signed claims, and revalidate mutable authorization state there.
  *
  * @param request
  *   The request for this mount phase: the browser's HTTP request when disconnected, or a GET
  *   request built from the join URL when connected.
  */
final case class LiveSessionMountRequest(request: Request):
  /** The request URL for this mount phase. */
  def url: URL = request.url

/** Produces session context immediately before disconnected and connected LiveView mount.
  *
  * The disconnected callback runs before the initial HTTP LiveView is built and mounted. Its
  * `Claims` are JSON-encoded into the signed root LiveView session token, then decoded for the
  * connected callback before a fresh connected LiveView is built and mounted. The connected
  * callback also runs for same-session live navigation. The two `Ctx` values are computed
  * independently; only `Claims` cross the HTTP-to-socket boundary.
  *
  * Claims are authenticated by the session signature but are not encrypted. They are visible to the
  * client and must not contain passwords, session cookie values, private access tokens, or other
  * secrets. Prefer a minimal public identifier, and revalidate revocation and authorization in
  * connected mount. Claim encoding and transport are framework internals, not a stable wire format.
  */
final case class LiveSessionMountAspect[R, -In, Claims, Ctx] private[scalive] (
  private[scalive] val disconnected: (
    (LiveSessionMountRequest, In) => ZIO[R, Response, (Claims, Ctx)]
  ),
  private[scalive] val connected: (
    (Claims, LiveSessionMountRequest, In) => ZIO[R, LiveSessionMountFailure, Ctx]
  )
)(using private[scalive] val claimsCodec: JsonCodec[Claims]):

  /** Transforms this aspect's context in both mount phases without changing its claims or input. */
  def map[Ctx2](f: Ctx => Ctx2): LiveSessionMountAspect[R, In, Claims, Ctx2] =
    LiveSessionMountAspect(
      (request, input) =>
        disconnected(request, input).map { case (claims, ctx) => claims -> f(ctx) },
      (claims, request, input) => connected(claims, request, input).map(f)
    )

  /** Annotates every log of a lifecycle that this aspect admits, from its context.
    *
    * `f` returns a ZIO aspect that adds log annotations, such as a zio-logging `LogAnnotation`
    * applied to a value or `ZIOAspect.annotated("user", id)`. Scalive applies it around the
    * disconnected page render, around every connected turn of the LiveView (mount, events,
    * messages, async results, subscriptions, and renders) and the fibers they start, and around
    * HTTP route handlers. Another lifecycle, such as the destination of a live navigation, gets the
    * annotations of its own admission. Use it only to annotate logs: it runs around all of that
    * work and must not change what it does. See [log
    * annotations](https://scalive.dev/guides/live-sessions-and-mount-aspects#annotate-logs).
    */
  def logAnnotated(
    f: Ctx => ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any]
  ): LiveSessionMountAspect[R, In, Claims, Ctx] =
    LiveSessionMountAspect(
      (request, input) =>
        disconnected(request, input).tap((_, ctx) => LiveLogAnnotations.record(f(ctx))),
      (claims, request, input) =>
        connected(claims, request, input).tap(ctx => LiveLogAnnotations.record(f(ctx)))
    )

  /** Composes two session aspects from left to right in both mount phases. */
  def ++[R1, Claims2, Ctx2, Result](
    that: LiveSessionMountAspect[R1, Ctx, Claims2, Ctx2]
  )(using
    JsonCodec[(Claims, Claims2)],
    ContextAppend.Aux[Ctx, Ctx2, Result]
  ): LiveSessionMountAspect[R & R1, In, (Claims, Claims2), Result] =
    val append = summon[ContextAppend.Aux[Ctx, Ctx2, Result]]
    LiveSessionMountAspect(
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
end LiveSessionMountAspect

/** Constructors for session mount aspects. */
object LiveSessionMountAspect:
  /** Creates a session aspect that consumes context from a preceding session aspect. */
  def make[R, In, Claims: JsonCodec, Ctx](
    disconnected: (LiveSessionMountRequest, In) => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveSessionMountRequest, In) => ZIO[R, LiveSessionMountFailure, Ctx]
  ): LiveSessionMountAspect[R, In, Claims, Ctx] =
    LiveSessionMountAspect(disconnected, connected)

  /** Creates a session aspect that does not consume context from a preceding aspect. */
  def fromRequest[R, Claims: JsonCodec, Ctx](
    disconnected: LiveSessionMountRequest => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveSessionMountRequest) => ZIO[R, LiveSessionMountFailure, Ctx]
  ): LiveSessionMountAspect[R, Any, Claims, Ctx] =
    LiveSessionMountAspect(
      (request, _) => disconnected(request),
      (claims, request, _) => connected(claims, request)
    )
