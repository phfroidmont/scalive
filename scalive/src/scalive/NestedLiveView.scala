package scalive

import java.util.concurrent.atomic.AtomicBoolean
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
  msgClassTag: ClassTag[Msg],
  sticky: Boolean,
  linkParentOnCrash: Boolean)

final private[scalive] case class NestedLiveViewRegistration(
  id: String,
  parentTopic: String,
  parentDomId: String,
  topic: String,
  session: String,
  sticky: Boolean,
  loading: Boolean = false,
  rendered: Option[HtmlElement[Any]] = None)

private[scalive] trait NestedLiveViewRuntime:
  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration]

  def afterParentRender: UIO[Unit] = ZIO.unit

private[scalive] object NestedLiveViewRuntime:
  object Disabled extends NestedLiveViewRuntime:
    def register[Msg, Model](
      spec: NestedLiveViewSpec[Msg, Model]
    ): Task[NestedLiveViewRegistration] =
      ZIO.fail(new IllegalStateException("nested LiveViews require a connected LiveView runtime"))

final private[scalive] case class NestedLiveViewEntry(
  id: String,
  parentTopic: String,
  sticky: Boolean,
  token: String,
  linkParentOnCrash: Boolean,
  start: (LiveContext, WebSocketMessage.Meta, URL, Boolean, UIO[Unit]) => RIO[
    Scope,
    Socket[?, ?]
  ])

final private[scalive] class SocketNestedLiveViewRuntime(
  parentTopic: String,
  parentDomId: String,
  tokenConfig: TokenConfig,
  entriesRef: Ref[Map[String, NestedLiveViewEntry]])
    extends NestedLiveViewRuntime:

  private val initialParentRender = new AtomicBoolean(true)

  override def afterParentRender: UIO[Unit] =
    ZIO.succeed(initialParentRender.set(false))

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"lv:${spec.id}"
    entriesRef.modify { entries =>
      val token = entries
        .get(topic)
        .filter(entry => entry.parentTopic == parentTopic && entry.sticky == spec.sticky)
        .map(_.token)
        .getOrElse(Token.sign(tokenConfig.secret, topic, "nested"))

      val entry = NestedLiveViewEntry(
        id = spec.id,
        parentTopic = parentTopic,
        sticky = spec.sticky,
        token = token,
        linkParentOnCrash = spec.linkParentOnCrash,
        start = (ctx, meta, initialUrl, enqueueInitReply, onCrash) =>
          Socket.start(
            topic,
            token,
            spec.liveView(),
            ctx,
            meta,
            tokenConfig,
            initialUrl,
            enqueueInitReply = enqueueInitReply,
            onCrash = onCrash
          )(using spec.msgClassTag)
      )

      val registration = NestedLiveViewRegistration(
        spec.id,
        parentTopic,
        parentDomId,
        topic,
        token,
        spec.sticky,
        loading = initialParentRender.get()
      )

      registration -> entries.updated(topic, entry)
    }
  end register
end SocketNestedLiveViewRuntime

final private[scalive] class DisconnectedNestedLiveViewRuntime(
  parentTopic: String,
  parentDomId: String,
  tokenConfig: TokenConfig,
  initialUrl: URL)
    extends NestedLiveViewRuntime:

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"lv:${spec.id}"
    for
      token         <- ZIO.succeed(Token.sign(tokenConfig.secret, topic, "nested"))
      streamRef     <- Ref.make(StreamRuntimeState.empty)
      flashRef      <- Ref.make(FlashRuntimeState.empty)
      componentsRef <- Ref.make(ComponentRuntimeState.empty)
      navigationRef <- Ref.make(Option.empty[LiveNavigationCommand])
      lv            <- ZIO.succeed(spec.liveView())
      hooksRef      <- Ref.make(LiveHookRuntimeState.root(lv.hooks))
      ctx = LiveContext(
              staticChanged = false,
              streams = new SocketStreamRuntime(streamRef),
              navigation = new SocketNavigationRuntime(navigationRef),
              flash = new SocketFlashRuntime(flashRef),
              components = new SocketComponentUpdateRuntime(componentsRef),
              hooks = new SocketLiveHookRuntime(hooksRef),
              nestedLiveViews = new DisconnectedNestedLiveViewRuntime(
                topic,
                spec.id,
                tokenConfig,
                initialUrl
              )
            )
      _               <- SocketFlashRuntime.resetNavigation(flashRef)
      _               <- navigationRef.set(None)
      initModel       <- lv.mount(ctx.mountContext[Msg, Model])
      mountNavigation <- navigationRef.getAndSet(None)
      lifecycle       <- LiveRoute.runInitialHandleParams(
                     lv,
                     initModel,
                     initialUrl,
                     ctx,
                     navigationRef,
                     flashRef,
                     mountNavigation
                   )
      rendered <- lifecycle match
                    case LiveRoute.InitialLifecycleOutcome.Render(model) =>
                      SocketComponentRuntime
                        .renderRoot(lv.render(model), componentsRef, ctx)
                        .tap(_ => ctx.hooks.runAfterRender[Msg, Model](model, ctx))
                    case LiveRoute.InitialLifecycleOutcome.Redirect(_) =>
                      ZIO.succeed(div())
    yield NestedLiveViewRegistration(
      spec.id,
      parentTopic,
      parentDomId,
      topic,
      token,
      spec.sticky,
      rendered = Some(rendered)
    )
    end for
  end register
end DisconnectedNestedLiveViewRuntime
