package scalive.runtime.connection

import zio.Task
import zio.ZIO
import zio.http.URL

import scalive.*

/** Typed root callbacks after route, session, and layout assembly. */
final private[scalive] case class RootLifecycle[Msg, Model](
  viewName: String,
  initialUrl: URL,
  hooks: LiveHooks[Msg, Model],
  pageTitle: Model => Option[String],
  layoutValues: Model => LiveLayoutValues,
  mount: MountContext[Msg, Model] => Task[Model],
  handleMessage: (Model, MessageContext[Msg, Model], Msg) => Task[Model],
  prepareParams: URL => Task[RootParamsHandler[Msg, Model]],
  view: Signal[(Model, URL)] => HtmlElement[Msg],
  connectedTurnGuard: LiveConnectedTurnGuard[Unit] = LiveConnectedTurnGuard.empty,
  acceptsPatch: URL => Boolean = _ => true,
  browserEvents: BrowserEvents[Msg] = BrowserEvents.none,
  logAnnotations: LiveLogAnnotations = LiveLogAnnotations.none)

final private[scalive] case class RootParamsHandler[Msg, Model](
  runHooks: Boolean,
  run: (Model, ParamsContext[Msg, Model]) => Task[Model])

private[scalive] object RootLifecycle:
  def ordinary[Msg, Model](
    liveView: LiveView[Msg, Model],
    initialUrl: URL = URL.root,
    connectedTurnGuard: LiveConnectedTurnGuard[Unit] = LiveConnectedTurnGuard.empty
  ): RootLifecycle[Msg, Model] =
    RootLifecycle(
      viewName = liveView.getClass.getName,
      initialUrl = initialUrl,
      hooks = liveView.hooks,
      browserEvents = liveView.browserEvents,
      pageTitle = liveView.pageTitle,
      layoutValues = liveView.layoutValues,
      mount = liveView.mount,
      handleMessage = (model, context, message) => liveView.handleMessage(model, context)(message),
      prepareParams =
        _ => ZIO.succeed(RootParamsHandler(runHooks = true, (model, _) => ZIO.succeed(model))),
      view = input => liveView.view(input.map(_._1)),
      connectedTurnGuard = connectedTurnGuard
    )
