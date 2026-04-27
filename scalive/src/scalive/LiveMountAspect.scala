package scalive

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

final case class LiveMountAspect[R, Claims, +Ctx] private[scalive] (
  disconnected: Request => ZIO[R, Response, (Claims, Ctx)],
  connected: (Claims, URL) => ZIO[R, Response, Ctx]
)(using private[scalive] val claimsCodec: JsonCodec[Claims]):

  def map[Ctx2](f: Ctx => Ctx2): LiveMountAspect[R, Claims, Ctx2] =
    LiveMountAspect(
      request => disconnected(request).map { case (claims, ctx) => claims -> f(ctx) },
      (claims, url) => connected(claims, url).map(f)
    )

  def ++[R1, Claims2, Ctx2](
    that: LiveMountAspect[R1, Claims2, Ctx2]
  )(using JsonCodec[(Claims, Claims2)]
  ): LiveMountAspect[R & R1, (Claims, Claims2), (Ctx, Ctx2)] =
    LiveMountAspect(
      request =>
        for
          left  <- disconnected(request)
          right <- that.disconnected(request)
        yield (left._1 -> right._1) -> (left._2 -> right._2),
      (claims, url) =>
        for
          left  <- connected(claims._1, url)
          right <- that.connected(claims._2, url)
        yield left -> right
    )

  private[scalive] def runtime: LiveMountAspectRuntime[R, Ctx] =
    val codec = claimsCodec
    new LiveMountAspectRuntime[R, Ctx]:
      def runDisconnected(request: Request): ZIO[R, Response, (Json, Ctx)] =
        disconnected(request).flatMap { case (claims, ctx) =>
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

      def runConnected(claims: Option[Json], url: URL): ZIO[R, Response, Ctx] =
        claims match
          case Some(value) =>
            given JsonCodec[Claims] = codec
            ZIO
              .fromEither(value.as[Claims])
              .mapError(error =>
                Response
                  .text(s"Could not decode LiveMountAspect claims: $error")
                  .status(Status.Unauthorized)
              )
              .flatMap(connected(_, url))
          case None =>
            ZIO.fail(Response(status = Status.Unauthorized))
  end runtime

end LiveMountAspect

object LiveMountAspect:
  def make[R, Claims: JsonCodec, Ctx](
    disconnected: Request => ZIO[R, Response, (Claims, Ctx)],
    connected: (Claims, URL) => ZIO[R, Response, Ctx]
  ): LiveMountAspect[R, Claims, Ctx] =
    LiveMountAspect(disconnected, connected)

  def load[R, Claims: JsonCodec, Ctx](
    disconnected: Request => ZIO[R, Response, (Claims, Ctx)]
  )(
    connected: (Claims, URL) => ZIO[R, Response, Ctx]
  ): LiveMountAspect[R, Claims, Ctx] =
    LiveMountAspect(disconnected, connected)

  final private[scalive] case class EmptyClaims() derives JsonCodec

  private[scalive] val identityRuntime: LiveMountAspectRuntime[Any, Unit] =
    LiveMountAspect(
      _ => ZIO.succeed(EmptyClaims() -> ()),
      (_, _) => ZIO.unit
    ).runtime

private[scalive] trait LiveMountAspectRuntime[R, +Ctx]:
  def runDisconnected(request: Request): ZIO[R, Response, (Json, Ctx)]
  def runConnected(claims: Option[Json], url: URL): ZIO[R, Response, Ctx]
