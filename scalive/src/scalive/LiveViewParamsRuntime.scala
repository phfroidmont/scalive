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
    ctx.hooks.runParams[Msg, Model](model, url, ctx).flatMap {
      case LiveHookResult.Halt(hookModel)     => ZIO.succeed(hookModel)
      case LiveHookResult.Continue(hookModel) =>
        lv.queryCodec
          .decode(url)
          .flatMap(query => lv.handleParams(hookModel, query, url, ctx.paramsContext[Msg, Model]))
          .catchSome { case error: LiveQueryCodec.DecodeError =>
            lv.handleParamsDecodeError(hookModel, error, url, ctx.paramsContext[Msg, Model])
          }
    }
