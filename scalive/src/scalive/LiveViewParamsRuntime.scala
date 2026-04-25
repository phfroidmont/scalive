package scalive

import zio.http.URL

private[scalive] object LiveViewParamsRuntime:
  def runHandleParams[Msg, Model](
    lv: LiveView[Msg, Model],
    model: Model,
    url: URL
  ): LiveIO[LiveView.ParamsContext, Model] =
    lv.queryCodec
      .decode(url)
      .flatMap(query => LiveIO.toZIO(lv.handleParams(model, query, url)))
      .catchSome { case error: LiveQueryCodec.DecodeError =>
        LiveIO.toZIO(lv.handleParamsDecodeError(model, error, url))
      }
