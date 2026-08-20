package scalive.runtime.connection

import zio.*
import zio.http.URL
import zio.json.JsonDecoder

import scalive.*
import scalive.runtime.resources.UploadRegistry

/** Immutable hook state owned by the session model. */
final private[connection] case class RootHookRegistry[Msg, Model](
  staticBrowser: Vector[RootHookRegistry.Browser[Msg, Model]],
  staticEvent: Vector[RootHookRegistry.Event[Msg, Model]],
  staticParams: Vector[RootHookRegistry.Params[Msg, Model]],
  staticInfo: Vector[RootHookRegistry.Event[Msg, Model]],
  staticAsync: Vector[RootHookRegistry.Async[Msg, Model]],
  staticAfterRender: Vector[RootHookRegistry.AfterRender[Msg, Model]],
  dynamicBrowser: Vector[(String, RootHookRegistry.Browser[Msg, Model])] = Vector.empty,
  dynamicEvent: Vector[(String, RootHookRegistry.Event[Msg, Model])] = Vector.empty,
  dynamicParams: Vector[(String, RootHookRegistry.Params[Msg, Model])] = Vector.empty,
  dynamicInfo: Vector[(String, RootHookRegistry.Event[Msg, Model])] = Vector.empty,
  dynamicAsync: Vector[(String, RootHookRegistry.Async[Msg, Model])] = Vector.empty,
  dynamicAfterRender: Vector[(String, RootHookRegistry.AfterRender[Msg, Model])] = Vector.empty):
  def browser     = staticBrowser ++ dynamicBrowser.map(_._2)
  def event       = staticEvent ++ dynamicEvent.map(_._2)
  def params      = staticParams ++ dynamicParams.map(_._2)
  def info        = staticInfo ++ dynamicInfo.map(_._2)
  def async       = staticAsync ++ dynamicAsync.map(_._2)
  def afterRender = staticAfterRender ++ dynamicAfterRender.map(_._2)

private[connection] object RootHookRegistry:
  trait Browser[Msg, Model]:
    def name: String
    def invoke(model: Model, raw: String, context: MessageContext[Msg, Model])
      : Either[String, LiveIO[Model]]

  trait Event[Msg, Model]:
    def invoke(model: Model, message: Msg, context: MessageContext[Msg, Model])
      : LiveIO[LiveHookResult[Model]]

  trait Params[Msg, Model]:
    def invoke(model: Model, url: URL, context: ParamsContext[Msg, Model])
      : LiveIO[LiveHookResult[Model]]

  trait Async[Msg, Model]:
    def invoke(model: Model, event: LiveAsyncEvent[Msg], context: MessageContext[Msg, Model])
      : LiveIO[LiveHookResult[Model]]

  trait AfterRender[Msg, Model]:
    def invoke(model: Model, context: AfterRenderContext[Msg, Model]): LiveIO[Unit]

  def browserHook[Msg, Model, A](
    event: BrowserToServerEvent[A],
    decoder: JsonDecoder[A],
    hook: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
  ): Browser[Msg, Model] = new Browser[Msg, Model]:
    val name                                                                   = event.value
    def invoke(model: Model, raw: String, context: MessageContext[Msg, Model]) =
      decoder.decodeJson(raw).map(value => hook(model, value, context))

  def fromStatic[Msg, Model](hooks: LiveHooks[Msg, Model]): RootHookRegistry[Msg, Model] =
    def collect(
      current: LiveHooks[Msg, Model],
      registry: RootHookRegistry[Msg, Model]
    ): RootHookRegistry[Msg, Model] = current match
      case _: LiveHooks.Empty[Msg, Model]               => registry
      case value: LiveHooks.BrowserEvent[Msg, Model, ?] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticBrowser =
          previous.staticBrowser :+ browserHook(
            value.event,
            value.decoder,
            value.handler
          )
        )
      case value: LiveHooks.Event[Msg, Model] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticEvent =
          previous.staticEvent :+ new Event[Msg, Model]:
            def invoke(model: Model, message: Msg, context: MessageContext[Msg, Model]) =
              value.hook(model, message, context)
        )
      case value: LiveHooks.Params[Msg, Model] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticParams =
          previous.staticParams :+ new Params[Msg, Model]:
            def invoke(model: Model, url: URL, context: ParamsContext[Msg, Model]) =
              value.hook(model, url, context)
        )
      case value: LiveHooks.Info[Msg, Model] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticInfo =
          previous.staticInfo :+ new Event[Msg, Model]:
            def invoke(model: Model, message: Msg, context: MessageContext[Msg, Model]) =
              value.hook(model, message, context)
        )
      case value: LiveHooks.Async[Msg, Model] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticAsync =
          previous.staticAsync :+ new Async[Msg, Model]:
            def invoke(
              model: Model,
              event: LiveAsyncEvent[Msg],
              context: MessageContext[Msg, Model]
            ) =
              value.hook(model, event, context)
        )
      case value: LiveHooks.AfterRender[Msg, Model] =>
        val previous = collect(value.previous, registry)
        previous.copy(staticAfterRender =
          previous.staticAfterRender :+ new AfterRender[Msg, Model]:
            def invoke(model: Model, context: AfterRenderContext[Msg, Model]) =
              value.hook(model, context)
        )

    collect(
      hooks,
      RootHookRegistry[Msg, Model](
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        dynamicBrowser = Vector.empty[(String, Browser[Msg, Model])],
        dynamicEvent = Vector.empty[(String, Event[Msg, Model])],
        dynamicParams = Vector.empty[(String, Params[Msg, Model])],
        dynamicInfo = Vector.empty[(String, Event[Msg, Model])],
        dynamicAsync = Vector.empty[(String, Async[Msg, Model])],
        dynamicAfterRender = Vector.empty[(String, AfterRender[Msg, Model])]
      )
    )
  end fromStatic

  def replace[A](entries: Vector[(String, A)], id: String, value: A): Vector[(String, A)] =
    val index = entries.indexWhere(_._1 == id)
    if index < 0 then entries :+ (id -> value)
    else entries.updated(index, id -> value)

  def detach[A](entries: Vector[(String, A)], id: String): Vector[(String, A)] =
    entries.filterNot(_._1 == id)
end RootHookRegistry

final private[connection] case class RootState[Msg, Model](
  model: Model,
  url: URL,
  hooks: RootHookRegistry[Msg, Model],
  flash: Map[FlashKind, String] = Map.empty,
  pageTitle: Option[String] = None,
  streams: StreamStore = StreamStore.empty,
  uploads: UploadRegistry = UploadRegistry.empty)
