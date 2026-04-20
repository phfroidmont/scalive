package scalive

import java.net.URI

import zio.json.ast.Json
import zio.stream.*

trait LiveView[Msg, Model]:
  import LiveView.*

  def init: LiveIO[InitContext, Model]
  def update(model: Model): Msg => LiveIO[UpdateContext, Model]
  def view(model: Dyn[Model]): HtmlElement
  def subscriptions(model: Model): ZStream[SubscriptionsContext, Nothing, Msg]

  def handleParams(model: Model, _params: Map[String, String], _uri: URI)
    : LiveIO[ParamsContext, Model] =
    val _ = (_params, _uri)
    model

  def interceptEvent(model: Model, _event: String, _value: Json)
    : LiveIO[InterceptContext, InterceptResult[Model]] =
    val _ = (_event, _value)
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
