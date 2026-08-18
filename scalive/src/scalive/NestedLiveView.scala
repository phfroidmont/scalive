package scalive

import java.util.concurrent.atomic.AtomicBoolean
import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*
import zio.stream.SubscriptionRef

import scalive.socket.ComponentRuntimeState
import scalive.socket.FlashRuntimeState
import scalive.socket.SocketComponentRuntime
import scalive.socket.SocketComponentUpdateRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketNavigationRuntime
import scalive.socket.SocketStreamRuntime
import scalive.socket.SocketUploadRuntime
import scalive.socket.SocketViewGraphRuntime
import scalive.socket.StreamRuntimeState
import scalive.socket.UploadRuntimeState

final private[scalive] case class NestedLiveViewSpec[Msg, Model](
  id: String,
  liveView: () => LiveView[Msg, Model],
  msgClassTag: ClassTag[Msg],
  sticky: Boolean,
  linkParentOnCrash: Boolean)

final private[scalive] case class SignalNestedLiveViewSpec[A, Msg, Model](
  id: String,
  value: Signal[A],
  liveView: A => LiveView[Msg, Model],
  msgClassTag: ClassTag[Msg],
  sticky: Boolean,
  linkParentOnCrash: A => Boolean)

final private[scalive] case class NestedLiveViewRegistration(
  id: String,
  parentTopic: String,
  parentDomId: String,
  topic: String,
  session: String,
  static: String = "",
  sticky: Boolean,
  loading: Boolean = false,
  rendered: Option[Mod[Any]] = None)

private[scalive] trait NestedLiveViewRuntime:
  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration]

  def renderTransaction[A](render: Task[A]): Task[A] = render

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
  generation: Long,
  token: String,
  linkParentOnCrash: Boolean,
  start: (LiveContext, WebSocketMessage.Meta, URL, Boolean, UIO[Unit]) => RIO[
    Scope,
    Socket[?, ?]
  ])

private[scalive] object NestedLiveViewTopology:
  def subtree(
    entries: Map[String, NestedLiveViewEntry],
    roots: Set[String]
  ): Set[String] =
    @annotation.tailrec
    def loop(frontier: Set[String], found: Set[String]): Set[String] =
      val children = entries.collect {
        case (topic, entry) if frontier.contains(entry.parentTopic) && !found.contains(topic) =>
          topic
      }.toSet
      if children.isEmpty then found else loop(children, found ++ children)

    loop(roots, roots)

final private[scalive] class SocketNestedLiveViewRuntime(
  parentTopic: String,
  parentDomId: String,
  tokenConfig: TokenConfig,
  entriesRef: Ref[Map[String, NestedLiveViewEntry]],
  renderPlansRef: Ref[Map[String, Map[String, NestedLiveViewEntry]]],
  generationRef: Ref[Map[String, Long]],
  socketsRef: SubscriptionRef[Map[String, Socket[?, ?]]],
  uploadOwnersRef: Ref[Map[String, String]],
  nestedJoinsRef: Ref[Map[String, Long]],
  topologyLock: Semaphore,
  runtimeTrace: RuntimeTrace,
  loadingOnInitialParentRender: Boolean)
    extends NestedLiveViewRuntime:

  private val initialParentRender = new AtomicBoolean(true)

  override def renderTransaction[A](render: Task[A]): Task[A] =
    beginRender *>
      render.exit.flatMap {
        case Exit.Success(value) => commitRender.as(value)
        case Exit.Failure(cause) => rollbackRender *> ZIO.failCause(cause)
      }

  private def beginRender: UIO[Unit] =
    topologyLock.withPermit(
      renderPlansRef.update(_.updated(parentTopic, Map.empty))
    )

  private def commitRender: UIO[Unit] =
    topologyLock
      .withPermit {
        renderPlansRef
          .modify(plans => plans.get(parentTopic) -> plans.removed(parentTopic)).flatMap {
            case Some(renderedEntries) =>
              for
                activeEntries  <- entriesRef.get
                currentSockets <- socketsRef.get
                parentIsRoot = !activeEntries.contains(parentTopic)
                removedRoots =
                  activeEntries.collect {
                    case (topic, entry)
                        if !renderedEntries.contains(topic) &&
                          (entry.parentTopic == parentTopic ||
                            (parentIsRoot && !activeEntries.contains(entry.parentTopic))) =>
                      topic
                  }.toSet
                replacedRoots =
                  renderedEntries.collect {
                    case (topic, renderedEntry)
                        if activeEntries
                          .get(topic).exists(_.generation != renderedEntry.generation) =>
                      topic
                  }.toSet
                retiredTopics = NestedLiveViewTopology.subtree(
                                  activeEntries,
                                  removedRoots ++ replacedRoots
                                )
                retiredEntries = retiredTopics
                                   .flatMap(topic => activeEntries.get(topic).map(topic -> _)).toMap
                retiredSocketTopics =
                  retiredEntries.collect {
                    case (topic, entry)
                        if currentSockets
                          .get(topic).flatMap(_.nestedGeneration).contains(entry.generation) =>
                      topic
                  }.toSet
                socketsToStop = retiredSocketTopics.toVector.flatMap(currentSockets.get)
                _ <- entriesRef.set((activeEntries -- retiredTopics) ++ renderedEntries)
                _ <- socketsRef.set(currentSockets -- retiredSocketTopics)
                _ <- nestedJoinsRef.update { joins =>
                       joins.filterNot { case (topic, generation) =>
                         retiredEntries.get(topic).exists(_.generation == generation)
                       }
                     }
                _ <-
                  uploadOwnersRef.update(
                    _.filterNot { case (_, ownerTopic) => retiredSocketTopics.contains(ownerTopic) }
                  )
              yield socketsToStop
            case None =>
              ZIO.dieMessage(s"nested LiveView render transaction missing for $parentTopic")
          }
      }.flatMap(sockets => ZIO.foreachDiscard(sockets)(_.shutdown)) *>
      ZIO.succeed(initialParentRender.set(false))

  private def rollbackRender: UIO[Unit] =
    topologyLock.withPermit(renderPlansRef.update(_.removed(parentTopic)))

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"lv:${spec.id}"
    topologyLock.withPermit {
      for
        plans           <- renderPlansRef.get
        renderedEntries <-
          ZIO
            .fromOption(plans.get(parentTopic))
            .orElseFail(
              new IllegalStateException(
                s"nested LiveView registered outside render transaction for $parentTopic"
              )
            )
        activeEntries <- entriesRef.get
        existing = activeEntries
                     .get(topic).filter(entry =>
                       (entry.sticky && spec.sticky) ||
                         (entry.parentTopic == parentTopic && entry.sticky == spec.sticky)
                     )
        generation <- existing.fold(
                        generationRef.modify { generations =>
                          val next = generations.getOrElse(topic, 0L) + 1L
                          next -> generations.updated(topic, next)
                        }
                      )(entry => ZIO.succeed(entry.generation))
        token = existing
                  .map(_.token).getOrElse(
                    Token.sign(tokenConfig.secret, topic, s"nested:$generation")
                  )
        entry = NestedLiveViewEntry(
                  id = spec.id,
                  parentTopic = parentTopic,
                  sticky = spec.sticky,
                  generation = generation,
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
                      onCrash = onCrash,
                      ownsPageTitle = false,
                      runtimeTrace = runtimeTrace,
                      nestedGeneration = Some(generation)
                    )(using spec.msgClassTag)
                )
        _ <- renderPlansRef.update(
               _.updated(parentTopic, renderedEntries.updated(topic, entry))
             )
      yield NestedLiveViewRegistration(
        spec.id,
        parentTopic,
        parentDomId,
        topic,
        token,
        sticky = spec.sticky,
        loading = loadingOnInitialParentRender && initialParentRender.get()
      )
    }
  end register
end SocketNestedLiveViewRuntime

final private[scalive] class DisconnectedNestedLiveViewRuntime(
  parentTopic: String,
  parentDomId: String,
  tokenConfig: TokenConfig,
  initialUrl: URL,
  csrfToken: Option[String] = None)
    extends NestedLiveViewRuntime:

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"lv:${spec.id}"
    for
      token         <- ZIO.succeed(Token.sign(tokenConfig.secret, topic, "nested:1"))
      uploadRef     <- Ref.make(UploadRuntimeState.empty)
      streamRef     <- Ref.make(StreamRuntimeState.empty)
      flashRef      <- Ref.make(FlashRuntimeState.empty)
      componentsRef <- Ref.make(ComponentRuntimeState.empty)
      navigationRef <- Ref.make(Option.empty[LiveNavigationCommand])
      lv            <- ZIO.succeed(spec.liveView())
      hooksRef      <- Ref.make(LiveHookRuntimeState.root(lv.hooks))
      ctx = LiveContext(
              staticChanged = false,
              uploads = new SocketUploadRuntime(uploadRef),
              streams = new SocketStreamRuntime(streamRef),
              navigation = new SocketNavigationRuntime(navigationRef),
              flash = new SocketFlashRuntime(flashRef),
              components = new SocketComponentUpdateRuntime(componentsRef),
              hooks = new SocketLiveHookRuntime(hooksRef),
              csrfToken = csrfToken,
              nestedLiveViews = new DisconnectedNestedLiveViewRuntime(
                topic,
                spec.id,
                tokenConfig,
                initialUrl,
                csrfToken
              )
            )
      _               <- SocketFlashRuntime.resetNavigation(flashRef)
      _               <- navigationRef.set(None)
      mounted         <- LiveRouteParamsRuntime.none[Any, Msg, Model].mount(lv, initialUrl, ctx)
      mountNavigation <- navigationRef.getAndSet(None)
      lifecycle       <- LiveRoute.runInitialHandleParams(
                     mounted.model,
                     initialUrl,
                     navigationRef,
                     flashRef,
                     mountNavigation,
                     mounted.handleInitialParams
                   )
      rendered <- lifecycle match
                    case LiveRoute.InitialLifecycleOutcome.Render(model) =>
                      val graph = ViewGraph.build[Model](modelSignal =>
                        csrfToken.fold(lv.view(modelSignal))(
                          CsrfProtection.injectForms(lv.view(modelSignal), _)
                        )
                      )
                      val render = SocketComponentRuntime
                        .evaluateViewGraph(
                          graph,
                          model,
                          SignalEvaluation.empty,
                          revision = 1L,
                          componentsRef,
                          ctx
                        ).map(evaluated =>
                          rawHtml(RenderSnapshot.renderHtml(evaluated.compiled))
                        ).ensuring(
                          ZIO.succeed(graph.dispose()) *>
                            SocketViewGraphRuntime.disposeComponents(componentsRef)
                        )
                      render.tap(_ => ctx.hooks.runAfterRender[Msg, Model](model, ctx))
                    case LiveRoute.InitialLifecycleOutcome.Redirect(_) =>
                      ZIO.succeed(Mod.Content.Tag(div()))
    yield NestedLiveViewRegistration(
      spec.id,
      parentTopic,
      parentDomId,
      topic,
      session = token,
      static = Token.sign(tokenConfig.secret, topic, "nested-static"),
      sticky = spec.sticky,
      rendered = Some(rendered)
    )
    end for
  end register
end DisconnectedNestedLiveViewRuntime
