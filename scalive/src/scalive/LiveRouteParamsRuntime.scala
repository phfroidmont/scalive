package scalive

import zio.*
import zio.http.URL
import zio.http.codec.PathCodec

private[scalive] trait LiveRouteParamsRuntime[A, Msg, Model]:
  def run(
    lv: LiveView[Msg, Model],
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[Model]

private[scalive] object LiveRouteParamsRuntime:
  def none[A, Msg, Model]: LiveRouteParamsRuntime[A, Msg, Model] =
    new LiveRouteParamsRuntime[A, Msg, Model]:
      def run(
        lv: LiveView[Msg, Model],
        model: Model,
        url: URL,
        ctx: LiveContext
      ): Task[Model] =
        ZIO.succeed(model)

  def routed[A, Msg, Model, Params](
    pathCodec: PathCodec[A],
    paramsDecoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsRuntime[A, Msg, Model] =
    new LiveRouteParamsRuntime[A, Msg, Model]:
      def run(
        lv: LiveView[Msg, Model],
        model: Model,
        url: URL,
        ctx: LiveContext
      ): Task[Model] =
        val routed = lv.asInstanceOf[LiveView.Routed[Msg, Model, Params]]
        ctx.hooks.runParams[Msg, Model](model, url, ctx).flatMap {
          case LiveHookResult.Halt(hookModel)     => ZIO.succeed(hookModel)
          case LiveHookResult.Continue(hookModel) =>
            decode(pathCodec, paramsDecoder, url)
              .flatMap(params =>
                routed.handleParams(hookModel, params, url, ctx.paramsContext[Msg, Model])
              )
              .catchSome { case error: LiveParamsCodec.DecodeError =>
                routed.handleParamsDecodeError(hookModel, error, url, ctx.paramsContext[Msg, Model])
              }
        }

  private def decode[A, Params](
    pathCodec: PathCodec[A],
    paramsDecoder: LiveParamsDecoder[A, Params],
    url: URL
  ): IO[LiveParamsCodec.DecodeError, Params] =
    ZIO
      .fromEither(
        pathCodec
          .decode(url.path).left.map(error =>
            LiveParamsCodec.DecodeError(
              s"Could not decode path '${url.path.encode}' for route '${pathCodec.render}': $error"
            )
          )
      )
      .flatMap(pathParams => paramsDecoder.decode(pathParams, url))
end LiveRouteParamsRuntime
