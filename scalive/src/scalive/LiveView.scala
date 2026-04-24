package scalive

import zio.http.URL
import zio.json.ast.Json
import zio.stream.*

trait LiveView[Msg, Model]:
  import LiveView.*

  def mount: LiveIO[InitContext, Model]
  def handleMessage(model: Model): Msg => LiveIO[UpdateContext, Model]
  def render(model: Model): HtmlElement[Msg]
  def subscriptions(model: Model): ZStream[SubscriptionsContext, Nothing, Msg]

  val queryCodec: LiveQueryCodec[?] = LiveQueryCodec.none

  def handleParams(model: Model, query: queryCodec.Out, url: URL): LiveIO[ParamsContext, Model] =
    val _ = (query, url)
    model

  def handleParamsDecodeError(
    model: Model,
    error: LiveQueryCodec.DecodeError,
    url: URL
  ): LiveIO[ParamsContext, Model] =
    val _ = (error, url)
    model

  def interceptEvent(model: Model, event: String, value: Json)
    : LiveIO[InterceptContext, InterceptResult[Model]] =
    val _ = (event, value)
    InterceptResult.cont(model)

object LiveView:
  type BaseContext       = LiveContext.BaseCapabilities
  type NavigationContext = LiveContext.NavigationCapabilities

  type InitContext          = BaseContext
  type SubscriptionsContext = BaseContext
  type UpdateContext        = NavigationContext
  type ParamsContext        = NavigationContext
  type InterceptContext     = NavigationContext

enum InterceptResult[Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json])

object InterceptResult:
  def cont[Model](model: Model): InterceptResult[Model] =
    InterceptResult.Continue(model)

  def halt[Model](model: Model): InterceptResult[Model] =
    InterceptResult.Halt(model, None)

  def haltReply[Model](model: Model, value: Json): InterceptResult[Model] =
    InterceptResult.Halt(model, Some(value))
