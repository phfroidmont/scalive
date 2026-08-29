package scalive.runtime.connection

import zio.Task
import zio.ZIO
import zio.http.URL

import scalive.*

/** Typed root callbacks after route, session, and layout assembly. */
final private[scalive] case class RootLifecycle[Msg, Model](
  initialUrl: URL,
  hooks: LiveHooks[Msg, Model],
  pageTitle: Model => Option[String],
  mount: MountContext[Msg, Model] => Task[Model],
  handleMessage: (Model, MessageContext[Msg, Model], Msg) => Task[Model],
  prepareParams: URL => Task[RootParamsHandler[Msg, Model]],
  view: Signal[(Model, URL)] => HtmlElement[Msg],
  connectedTurnGuard: LiveConnectedTurnGuard[Unit] = LiveConnectedTurnGuard.empty)

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
      initialUrl = initialUrl,
      hooks = liveView.hooks,
      pageTitle = liveView.pageTitle,
      mount = liveView.mount,
      handleMessage = (model, context, message) => liveView.handleMessage(model, context)(message),
      prepareParams =
        _ => ZIO.succeed(RootParamsHandler(runHooks = false, (model, _) => ZIO.succeed(model))),
      view = input => liveView.view(input.map(_._1)),
      connectedTurnGuard = connectedTurnGuard
    )
