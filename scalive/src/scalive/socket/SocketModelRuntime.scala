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
    initial: Option[LiveNavigationCommand] = None
  )(
    effect: Task[A]
  ): Task[(A, Option[LiveNavigationCommand])] =
    for
      _          <- state.navigationRef.set(initial)
      exit       <- effect.exit
      navigation <- state.navigationRef.getAndSet(None)
      value      <- exit match
                 case Exit.Success(v) => ZIO.succeed(v)
                 case Exit.Failure(c) => ZIO.failCause(c)
    yield (value, navigation)

  def applyInterceptHalt[Msg, Model](
    rendered: RenderedView[Msg],
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
                   rendered,
                   interceptModel,
                   command,
                   meta,
                   state
                 )
             case None => ZIO.unit
    yield ()

  def applyBoundEvent[Msg, Model](
    rendered: RenderedView[Msg],
    interceptModel: Model,
    event: Payload.Event,
    carriedNavigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    rendered.bindings.get(event.event) match
      case Some(binding) =>
        binding(event.params) match
          case Right(message) =>
            for
              (updatedModel, navigation) <-
                captureNavigation(state, carriedNavigation)(
                  LiveIO
                    .toZIO(state.lv.update(interceptModel)(message))
                    .provide(ZLayer.succeed(state.ctx))
                )
              _ <- navigation match
                     case Some(command) =>
                       publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
                         state.patchRedirectCountRef.set(0) *>
                         SocketInbound.handleNavigationCommand(
                           rendered,
                           updatedModel,
                           command,
                           meta,
                           state
                         )
                     case None =>
                       for
                         diff <- updateModelAndSubscriptions(rendered, updatedModel, state)
                         _ <- publishPayload(Payload.okReply(LiveResponse.Diff(diff)), meta, state)
                       yield ()
            yield ()
          case Left(error) =>
            handleInvalidOrMissingBinding(
              rendered,
              event.event,
              Some(error),
              interceptModel,
              carriedNavigation,
              meta,
              state
            )
      case None =>
        handleInvalidOrMissingBinding(
          rendered,
          event.event,
          None,
          interceptModel,
          carriedNavigation,
          meta,
          state
        )

  def updateModelAndSubscriptions[Msg, Model](
    rendered: RenderedView[Msg],
    model: Model,
    state: RuntimeState[Msg, Model]
  ): Task[Diff] =
    for
      _ <- state.lvStreamRef.set(
             state.lv.subscriptions(model).provideLayer(ZLayer.succeed(state.ctx))
           )
      nextEl       = state.lv.view(model)
      diff         = TreeDiff.diff(rendered.el, nextEl)
      nextRendered = RenderedView(
                       el = nextEl,
                       bindings = BindingRegistry.collect[Msg](nextEl)(using state.msgClassTag)
                     )
      _      <- state.ref.set((model, nextRendered))
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
    yield renderedDiff

  def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    state.outHub.publish(payload -> meta).unit

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
    rendered: RenderedView[Msg],
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
            rendered = rendered,
            model = model,
            command = command,
            meta = meta,
            state = state
          )
      case None =>
        val detail = error.getOrElse("unknown binding id")
        ZIO.logWarning(s"Ignoring binding '$bindingId': $detail") *>
          publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)

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
