package scalive

import zio.http.URL
import zio.json.JsonDecoder

enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)

object LiveHookResult:
  def cont[Model](model: Model): LiveHookResult[Model] = Continue(model)
  def halt[Model](model: Model): LiveHookResult[Model] = Halt(model)

/** Immutable declarations installed when a root lifecycle starts. */
sealed trait LiveHooks[Msg, Model]:
  final def onBrowserEvent[A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
  ): LiveHooks[Msg, Model] =
    LiveHooks.BrowserEvent(this, event, handler, summon[JsonDecoder[A]])

  final def onEvent(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Event(this, hook)

  final def onParams(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Params(this, hook)

  final def onInfo(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveHooks[Msg, Model] = LiveHooks.Info(this, hook)

  final def onAsync(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveHooks[Msg, Model] = LiveHooks.Async(this, hook)

  final def afterRender(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveHooks[Msg, Model] = LiveHooks.AfterRender(this, hook)

object LiveHooks:
  final private[scalive] case class Empty[Msg, Model]() extends LiveHooks[Msg, Model]
  final private[scalive] case class BrowserEvent[Msg, Model, A](
    previous: LiveHooks[Msg, Model],
    event: BrowserToServerEvent[A],
    handler: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model],
    decoder: JsonDecoder[A])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Event[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Params[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Info[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class Async[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])
      extends LiveHooks[Msg, Model]
  final private[scalive] case class AfterRender[Msg, Model](
    previous: LiveHooks[Msg, Model],
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit])
      extends LiveHooks[Msg, Model]

  def empty[Msg, Model]: LiveHooks[Msg, Model] = Empty()
end LiveHooks

/** Immutable declarations installed when a component instance starts. */
sealed trait ComponentLiveHooks[Props, Msg, Model]:
  final def onBrowserEvent[A: JsonDecoder](
    event: BrowserToServerEvent[A]
  )(
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => LiveIO[Model]
  ): ComponentLiveHooks[Props, Msg, Model] =
    ComponentLiveHooks.BrowserEvent(this, event, handler, summon[JsonDecoder[A]])

  final def onEvent(
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.Event(this, hook)

  final def onAsync(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.Async(this, hook)

  final def afterRender(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
  ): ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.AfterRender(this, hook)

object ComponentLiveHooks:
  final private[scalive] case class Empty[Props, Msg, Model]()
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class BrowserEvent[Props, Msg, Model, A](
    previous: ComponentLiveHooks[Props, Msg, Model],
    event: BrowserToServerEvent[A],
    handler: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => LiveIO[Model],
    decoder: JsonDecoder[A])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class Event[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class Async[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ])
      extends ComponentLiveHooks[Props, Msg, Model]
  final private[scalive] case class AfterRender[Props, Msg, Model](
    previous: ComponentLiveHooks[Props, Msg, Model],
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit])
      extends ComponentLiveHooks[Props, Msg, Model]

  def empty[Props, Msg, Model]: ComponentLiveHooks[Props, Msg, Model] = Empty()

trait RootHooks[Msg, Model]:
  def browserEvent: RootBrowserEventHooks[Msg, Model]
  def event: RootEventHooks[Msg, Model]
  def params: RootParamsHooks[Msg, Model]
  def info: RootInfoHooks[Msg, Model]
  def async: RootAsyncHooks[Msg, Model]
  def afterRender: RootAfterRenderHooks[Msg, Model]

trait RootBrowserEventHooks[Msg, Model]:
  def attach[A: JsonDecoder](
    id: String,
    event: BrowserToServerEvent[A]
  )(
    hook: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootEventHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootParamsHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootInfoHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootAsyncHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootAfterRenderHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentHooks[Props, Msg, Model]:
  def browserEvent: ComponentBrowserEventHooks[Props, Msg, Model]
  def event: ComponentEventHooks[Props, Msg, Model]
  def async: ComponentAsyncHooks[Props, Msg, Model]
  def afterRender: ComponentAfterRenderHooks[Props, Msg, Model]

trait ComponentBrowserEventHooks[Props, Msg, Model]:
  def attach[A: JsonDecoder](
    id: String,
    event: BrowserToServerEvent[A]
  )(
    hook: (Props, Model, A, ComponentMessageContext[Props, Msg, Model]) => LiveIO[Model]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentEventHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, Msg, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentAsyncHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentAfterRenderHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Unit]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]
