package scalive

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

final case class LiveMountRequest[+A](params: A, request: Request):
  def url: URL = request.url

/** Failure returned by the connected LiveMountAspect phase.
  *
  * The client only receives Phoenix-compatible redirect, unauthorized, or stale join failures.
  * Optional reasons are kept server-side for debugging and logs.
  */
enum LiveMountFailure:
  case Redirect(to: URL)
  case Unauthorized(reason: Option[String])
  case Stale(reason: Option[String])

object LiveMountFailure:
  def redirect(to: URL): LiveMountFailure =
    LiveMountFailure.Redirect(to)

  def unauthorized: LiveMountFailure =
    LiveMountFailure.Unauthorized(None)

  def unauthorized(reason: String): LiveMountFailure =
    LiveMountFailure.Unauthorized(Some(reason))

  def stale: LiveMountFailure =
    LiveMountFailure.Stale(None)

  def stale(reason: String): LiveMountFailure =
    LiveMountFailure.Stale(Some(reason))

/** Runs before disconnected and connected LiveView mount.
  *
  * `Claims` are signed into the root LiveView session token between HTTP render and websocket join.
  * They are tamper-proof, but not encrypted, so they must not contain secrets.
  *
  * If the connected phase fails, `LiveMountFailure` is mapped to the matching websocket reply.
  */
final case class LiveMountAspect[R, A, -In, Claims, Ctx] private[scalive] (
  disconnected: (LiveMountRequest[A], In) => ZIO[R, Response, (Claims, Ctx)],
  connected: (Claims, LiveMountRequest[A], In) => ZIO[R, LiveMountFailure, Ctx]
)(using private[scalive] val claimsCodec: JsonCodec[Claims]):

  def map[Ctx2](f: Ctx => Ctx2): LiveMountAspect[R, A, In, Claims, Ctx2] =
    LiveMountAspect(
      (request, input) =>
        disconnected(request, input).map { case (claims, ctx) => claims -> f(ctx) },
      (claims, request, input) => connected(claims, request, input).map(f)
    )

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

object LiveMountAspect:
  def make[R, A, In, Claims: JsonCodec, Ctx](
    disconnected: (LiveMountRequest[A], In) => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveMountRequest[A], In) => ZIO[R, LiveMountFailure, Ctx]
  ): LiveMountAspect[R, A, In, Claims, Ctx] =
    LiveMountAspect(disconnected, connected)

  def fromRequest[R, A, Claims: JsonCodec, Ctx](
    disconnected: LiveMountRequest[A] => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, LiveMountRequest[A]) => ZIO[R, LiveMountFailure, Ctx]
  ): LiveMountAspect[R, A, Any, Claims, Ctx] =
    LiveMountAspect(
      (request, _) => disconnected(request),
      (claims, request, _) => connected(claims, request)
    )

  final private[scalive] case class EmptyClaims() derives JsonCodec

  private[scalive] def identityPipeline[A, Ctx]: LiveMountPipeline[Any, A, Ctx, Ctx] =
    LiveMountPipeline.identity[A, Ctx]

trait ContextAppend[In, Out]:
  type Result
  def append(input: In, output: Out): Result
  def left(result: Result): In

object ContextAppend extends LowPriorityContextAppend:
  type Aux[In, Out, Result0] = ContextAppend[In, Out] { type Result = Result0 }

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

private[scalive] trait LiveMountPipeline[R, A, -In, Ctx]:
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
