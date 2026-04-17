package scalive

import java.net.URI

import zio.*
import zio.json.ast.Json
import zio.stream.*

// TODO implement all LiveView functions
final case class LiveContext(staticChanged: Boolean)
object LiveContext:
  def staticChanged: URIO[LiveContext, Boolean] = ZIO.serviceWith[LiveContext](_.staticChanged)

trait LiveView[Msg, Model]:
  def init: Model | RIO[LiveContext, Model]
  def update(model: Model): Msg => Model | RIO[LiveContext, Model]
  def view(model: Dyn[Model]): HtmlElement
  def subscriptions(model: Model): ZStream[LiveContext, Nothing, Msg]
  def handleParams(model: Model, _params: Map[String, String], _uri: URI)
    : ParamsResult[Model] | RIO[LiveContext, ParamsResult[Model]] =
    ParamsResult.cont(model)
  def handleHook(model: Model, _event: String, _value: Json)
    : HookResult[Model] | RIO[LiveContext, HookResult[Model]] =
    HookResult.cont(model)

enum ParamsResult[Model]:
  case Continue(model: Model)
  case PushPatch(model: Model, to: String)
  case ReplacePatch(model: Model, to: String)

object ParamsResult:
  def cont[Model](model: Model): ParamsResult[Model] =
    ParamsResult.Continue(model)

  def pushPatch[Model](model: Model, to: String): ParamsResult[Model] =
    ParamsResult.PushPatch(model, to)

  def replacePatch[Model](model: Model, to: String): ParamsResult[Model] =
    ParamsResult.ReplacePatch(model, to)

enum HookResult[Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json])

object HookResult:
  def cont[Model](model: Model): HookResult[Model]                   = HookResult.Continue(model)
  def halt[Model](model: Model): HookResult[Model]                   = HookResult.Halt(model, None)
  def haltReply[Model](model: Model, value: Json): HookResult[Model] =
    HookResult.Halt(model, Some(value))
