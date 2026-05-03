package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketModelRuntime:
  def captureNavigation[Msg, Model, A](
    state: RuntimeState[Msg, Model],
    initial: Option[LiveNavigationCommand] = None,
    resetFlash: Boolean = true
  )(
    effect: Task[A]
  ): Task[(A, Option[LiveNavigationCommand])] =
    for
      _          <- ZIO.when(resetFlash)(SocketFlashRuntime.resetNavigation(state.flashRef))
      _          <- state.navigationRef.set(initial)
      exit       <- effect.exit
      navigation <- state.navigationRef.getAndSet(None)
      value      <- exit match
                 case Exit.Success(v) => ZIO.succeed(v)
                 case Exit.Failure(c) => ZIO.failCause(c)
    yield (value, navigation)

  def applyInterceptHalt[Msg, Model](
    rendered: RenderedView,
    interceptModel: Model,
    reply: Option[Json],
    navigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      diff <- updateModelAndSubscriptions(rendered, interceptModel, state)
      payload = interceptReplyPayload(reply, diff)
      _ <- publishPayload(payload, meta, state)
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(
                   interceptModel,
                   command,
                   meta,
                   state
                 )
             case None => SocketFlashRuntime.resetNavigation(state.flashRef)
    yield ()

  def applyBoundEvent[Msg, Model](
    rendered: RenderedView,
    interceptModel: Model,
    event: Payload.Event,
    carriedNavigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    rendered.bindings.get(event.event) match
      case Some(binding) =>
        binding(event.bindingPayload) match
          case Right(ComponentMessage(cid, message)) if event.cid.contains(cid) =>
            SocketComponentRuntime
              .handleComponentMessage(
                cid,
                message,
                LiveEvent.fromPayload(event),
                rendered,
                meta,
                state
              ).flatMap {
                case true  => ZIO.unit
                case false => publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)
              }
          case Right(ComponentMessage(cid, _)) =>
            handleInvalidOrMissingBinding(
              event.event,
              Some(s"Binding '${event.event}' targets component $cid without matching event cid"),
              interceptModel,
              carriedNavigation,
              meta,
              state
            )
          case Right(ComponentTargetMessage(componentClass, message)) =>
            event.cid match
              case Some(cid) =>
                SocketComponentRuntime
                  .handleComponentTargetMessage(
                    componentClass,
                    cid,
                    message,
                    LiveEvent.fromPayload(event),
                    rendered,
                    meta,
                    state
                  ).flatMap {
                    case true  => ZIO.unit
                    case false =>
                      handleInvalidOrMissingBinding(
                        event.event,
                        Some(
                          s"Binding '${event.event}' targets ${componentClass.getName} but event cid $cid does not match"
                        ),
                        interceptModel,
                        carriedNavigation,
                        meta,
                        state
                      )
                  }
              case None =>
                handleInvalidOrMissingBinding(
                  event.event,
                  Some(
                    s"Binding '${event.event}' targets ${componentClass.getName} without event cid"
                  ),
                  interceptModel,
                  carriedNavigation,
                  meta,
                  state
                )
          case Right(message) =>
            state.msgClassTag.unapply(message) match
              case Some(parentMessage) =>
                for
                  (updatedModel, navigation) <-
                    captureNavigation(state, carriedNavigation)(
                      state.ctx.hooks
                        .runEvent(
                          interceptModel,
                          parentMessage,
                          LiveEvent.fromPayload(event),
                          state.ctx
                        ).flatMap {
                          case LiveEventHookResult.Continue(hookModel) =>
                            state.lv
                              .handleMessage(
                                hookModel,
                                state.ctx.messageContext[Msg, Model]
                              )(parentMessage)
                              .map(LiveEventHookResult.Continue(_))
                          case halt @ LiveEventHookResult.Halt(_, _) => ZIO.succeed(halt)
                        }
                    )
                  _ <- updatedModel match
                         case LiveEventHookResult.Halt(hookModel, reply) =>
                           applyInterceptHalt(
                             rendered,
                             hookModel,
                             reply,
                             navigation,
                             meta,
                             state
                           )
                         case LiveEventHookResult.Continue(hookModel) =>
                           navigation match
                             case Some(command) =>
                               publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
                                 state.patchRedirectCountRef.set(0) *>
                                 SocketInbound.handleNavigationCommand(
                                   hookModel,
                                   command,
                                   meta,
                                   state
                                 )
                             case None =>
                               for
                                 diff <- updateModelAndSubscriptions(rendered, hookModel, state)
                                 _    <-
                                   publishPayload(
                                     Payload.okReply(LiveResponse.Diff(diff)),
                                     meta,
                                     state
                                   )
                                 _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                               yield ()
                yield ()
              case None =>
                handleInvalidOrMissingBinding(
                  event.event,
                  Some(
                    s"Binding '${event.event}' produced ${message.getClass.getName}, expected ${state.msgClassTag.runtimeClass.getName}"
                  ),
                  interceptModel,
                  carriedNavigation,
                  meta,
                  state
                )
          case Left(error) =>
            handleInvalidOrMissingBinding(
              event.event,
              Some(error),
              interceptModel,
              carriedNavigation,
              meta,
              state
            )
      case None =>
        handleInvalidOrMissingBinding(
          event.event,
          None,
          interceptModel,
          carriedNavigation,
          meta,
          state
        )

  def updateModelAndSubscriptions[Msg, Model](
    rendered: RenderedView,
    model: Model,
    state: RuntimeState[Msg, Model]
  ): Task[Diff] =
    for
      currentUrl <- state.currentUrlRef.get
      nextRoot   <- SocketComponentRuntime.renderRoot(state.renderRoot(model, currentUrl), state)
      nextCompiled = RenderSnapshot.compile(nextRoot)
      diff         = TreeDiff.diff(rendered.compiled, nextCompiled)
      nextRendered = RenderedView(
                       compiled = nextCompiled,
                       bindings = BindingRegistry.collect[Any](nextCompiled)
                     )
      events <- SocketClientEventRuntime.drain(state.clientEventsRef)
      title  <- state.titleRef.getAndSet(None)
      _      <- SocketStreamRuntime.prune(state.streamRef)
      renderedDiff = withTitle(withClientEvents(diff, events), title)
      _ <- state.componentCidsRef.update(
             _ ++ (
               renderedDiff match
                 case Diff.Tag(_, _, _, _, _, components, _, _) => components.keySet
                 case _                                         => Set.empty[Int]
             )
           )
      afterRenderModel <- state.ctx.hooks.runAfterRender[Msg, Model](model, state.ctx)
      _                <- state.ref.set((afterRenderModel, nextRendered))
    yield renderedDiff

  def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    state.outQueue.offer(payload -> meta).unit

  private def interceptReplyPayload(
    reply: Option[Json],
    diff: Diff
  ): Payload =
    reply match
      case Some(replyValue) =>
        Payload.okReply(LiveResponse.InterceptReply(replyValue, Option.when(!diff.isEmpty)(diff)))
      case None if !diff.isEmpty =>
        Payload.okReply(LiveResponse.Diff(diff))
      case None =>
        Payload.okReply(LiveResponse.Empty)

  private def handleInvalidOrMissingBinding[Msg, Model](
    bindingId: String,
    error: Option[String],
    model: Model,
    carriedNavigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    carriedNavigation match
      case Some(command) =>
        publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
          state.patchRedirectCountRef.set(0) *>
          SocketInbound.handleNavigationCommand(
            model = model,
            command = command,
            meta = meta,
            state = state
          )
      case None =>
        val detail = error.getOrElse("unknown binding id")
        ZIO.logWarning(s"Ignoring binding '$bindingId': $detail") *>
          publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
          SocketFlashRuntime.resetNavigation(state.flashRef)

  private[socket] def withClientEvents(diff: Diff, events: Seq[Diff.Event]): Diff =
    diff match
      case Diff.Tag(
            static,
            dynamic,
            existingEvents,
            root,
            title,
            components,
            templates,
            templateRef
          ) if events.nonEmpty =>
        Diff.Tag(
          static = static,
          dynamic = dynamic,
          events = existingEvents ++ events,
          root = root,
          title = title,
          components = components,
          templates = templates,
          templateRef = templateRef
        )
      case _ =>
        diff

  private[socket] def withTitle(diff: Diff, title: Option[String]): Diff =
    (diff, title) match
      case (
            Diff.Tag(
              static,
              dynamic,
              events,
              root,
              _,
              components,
              templates,
              templateRef
            ),
            Some(nextTitle)
          ) =>
        Diff.Tag(
          static = static,
          dynamic = dynamic,
          events = events,
          root = root,
          title = Some(nextTitle),
          components = components,
          templates = templates,
          templateRef = templateRef
        )
      case _ =>
        diff
end SocketModelRuntime
