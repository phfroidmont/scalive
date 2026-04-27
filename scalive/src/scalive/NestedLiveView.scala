package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*

import scalive.socket.ComponentRuntimeState
import scalive.socket.FlashRuntimeState
import scalive.socket.SocketComponentRuntime
import scalive.socket.SocketComponentUpdateRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketNavigationRuntime
import scalive.socket.SocketStreamRuntime
import scalive.socket.StreamRuntimeState

final private[scalive] case class NestedLiveViewSpec[Msg, Model](
  id: String,
  liveView: () => LiveView[Msg, Model],
  msgClassTag: ClassTag[Msg])

final private[scalive] case class NestedLiveViewRegistration(
  id: String,
  parentTopic: String,
  topic: String,
  session: String,
  rendered: Option[HtmlElement[Any]] = None)

trait NestedLiveViewRuntime:
  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration]

object NestedLiveViewRuntime:
  object Disabled extends NestedLiveViewRuntime:
    def register[Msg, Model](
      spec: NestedLiveViewSpec[Msg, Model]
    ): Task[NestedLiveViewRegistration] =
      ZIO.fail(new IllegalStateException("nested LiveViews require a connected LiveView runtime"))

final private[scalive] case class NestedLiveViewEntry(
  parentTopic: String,
  token: String,
  start: (LiveContext, WebSocketMessage.Meta, URL) => RIO[Scope, Socket[?, ?]])

final private[scalive] class SocketNestedLiveViewRuntime(
  parentTopic: String,
  tokenConfig: TokenConfig,
  entriesRef: Ref[Map[String, NestedLiveViewEntry]])
    extends NestedLiveViewRuntime:

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"$parentTopic-${spec.id}"
    for
      token = Token.sign(tokenConfig.secret, topic, "nested")
      entry = NestedLiveViewEntry(
                parentTopic = parentTopic,
                token = token,
                start = (ctx, meta, initialUrl) =>
                  Socket.start(
                    topic,
                    token,
                    spec.liveView(),
                    ctx,
                    meta,
                    tokenConfig,
                    initialUrl
                  )(using spec.msgClassTag)
              )
      _ <- entriesRef.update(_.updated(topic, entry))
    yield NestedLiveViewRegistration(spec.id, parentTopic, topic, token)

final private[scalive] class DisconnectedNestedLiveViewRuntime(
  parentTopic: String,
  tokenConfig: TokenConfig,
  initialUrl: URL)
    extends NestedLiveViewRuntime:

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"$parentTopic-${spec.id}"
    for
      token         <- ZIO.succeed(Token.sign(tokenConfig.secret, topic, "nested"))
      streamRef     <- Ref.make(StreamRuntimeState.empty)
      flashRef      <- Ref.make(FlashRuntimeState.empty)
      componentsRef <- Ref.make(ComponentRuntimeState.empty)
      navigationRef <- Ref.make(Option.empty[LiveNavigationCommand])
      ctx = LiveContext(
              staticChanged = false,
              streams = new SocketStreamRuntime(streamRef),
              navigation = new SocketNavigationRuntime(navigationRef),
              flash = new SocketFlashRuntime(flashRef),
              components = new SocketComponentUpdateRuntime(componentsRef),
              nestedLiveViews = new DisconnectedNestedLiveViewRuntime(
                topic,
                tokenConfig,
                initialUrl
              )
            )
      lv        <- ZIO.succeed(spec.liveView())
      initModel <- LiveIO.toZIO(lv.mount).provide(ZLayer.succeed(ctx))
      lifecycle <- LiveRoute.runInitialHandleParams(
                     lv,
                     initModel,
                     initialUrl,
                     ctx,
                     navigationRef
                   )
      rendered <- lifecycle match
                    case LiveRoute.InitialLifecycleOutcome.Render(model) =>
                      SocketComponentRuntime.renderRoot(lv.render(model), componentsRef, ctx)
                    case LiveRoute.InitialLifecycleOutcome.Redirect(_) =>
                      ZIO.succeed(div())
    yield NestedLiveViewRegistration(spec.id, parentTopic, topic, token, Some(rendered))
    end for
  end register
end DisconnectedNestedLiveViewRuntime
