package scalive

import zio.*
import zio.http.URL

private[scalive] object LiveViewParamsRuntime:
  def runHandleParams[Msg, Model](
    lv: LiveView[Msg, Model],
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[Model] =
    ctx.hooks.runParams(model, url, ctx).flatMap {
      case LiveHookResult.Halt(hookModel)     => ZIO.succeed(hookModel)
      case LiveHookResult.Continue(hookModel) =>
        lv.queryCodec
          .decode(url)
          .flatMap(query => LiveIO.toZIO(lv.handleParams(hookModel, query, url)))
          .catchSome { case error: LiveQueryCodec.DecodeError =>
            LiveIO.toZIO(lv.handleParamsDecodeError(hookModel, error, url))
          }
          .provide(ZLayer.succeed(ctx))
    }
