package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketModelRuntime:
  def currentModelAndRendered[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): UIO[(Model, RenderedView)] =
    state.ref.get.zipWith(state.pendingNavigationModelRef.get) {
      case ((committedModel, rendered), pendingModel) =>
        pendingModel.getOrElse(committedModel) -> rendered
    }

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
      diff <- updateModelAndSubscriptions(rendered, interceptModel, state, meta.traceOperation)
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
        event.bindingPayload.flatMap(binding(_)) match
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
          case Right(ComponentInstanceMessage(identity, message)) =>
            SocketComponentRuntime
              .handleComponentInstanceMessage(
                identity,
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
                      s"Binding '${event.event}' targets ${identity.componentClass.getName} with id '${identity.id}', but that instance does not exist"
                    ),
                    interceptModel,
                    carriedNavigation,
                    meta,
                    state
                  )
              }
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
                  _ <- RuntimeTraceOperation.event(
                         meta.traceOperation,
                         RuntimeTraceStage.BindingResolution,
                         "Event binding resolved"
                       )
                  _ <- RuntimeTraceOperation.message(
                         meta.traceOperation,
                         RuntimeTraceStage.TypedMessage,
                         "Binding produced a typed message",
                         parentMessage
                       )
                  _ <- RuntimeTraceOperation.event(
                         meta.traceOperation,
                         RuntimeTraceStage.LifecycleStarted,
                         "Event lifecycle and message handler started"
                       )
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
                  _ <- RuntimeTraceOperation.event(
                         meta.traceOperation,
                         RuntimeTraceStage.LifecycleCompleted,
                         "Event lifecycle and message handler completed"
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
                                 diff <- updateModelAndSubscriptions(
                                           rendered,
                                           hookModel,
                                           state,
                                           meta.traceOperation
                                         )
                                 _ <- publishPayload(
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
        event.cid match
          case Some(cid) =>
            SocketComponentRuntime
              .handleComponentRawEvent(
                cid,
                LiveEvent.fromPayload(event),
                rendered,
                meta,
                state
              ).flatMap {
                case true  => ZIO.unit
                case false =>
                  handleInvalidOrMissingBinding(
                    event.event,
                    None,
                    interceptModel,
                    carriedNavigation,
                    meta,
                    state
                  )
              }
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
    state: RuntimeState[Msg, Model],
    traceOperation: RuntimeTraceOperation = RuntimeTraceOperation.Disabled
  ): Task[Diff] =
    state.componentsRef.get.flatMap(previousComponents =>
      state.ctx.nestedLiveViews
        .renderTransaction(
          updateModelAndSubscriptionsUnchecked(rendered, model, state, traceOperation)
        )
        .onError(_ => restoreComponentsAfterFailure(state.componentsRef, previousComponents))
    )

  private def restoreComponentsAfterFailure(
    componentsRef: Ref[ComponentRuntimeState],
    previous: ComponentRuntimeState
  ): UIO[Unit] =
    componentsRef.get.flatMap { candidate =>
      val newGraphs = candidate.instances.collect {
        case (identity, instance)
            if previous.instances
              .get(identity).forall(previousInstance =>
                previousInstance.viewGraph ne instance.viewGraph
              ) =>
          instance.viewGraph
      }
      ZIO.succeed(newGraphs.foreach(_.dispose())) *> componentsRef.set(previous)
    }

  private def updateModelAndSubscriptionsUnchecked[Msg, Model](
    rendered: RenderedView,
    model: Model,
    state: RuntimeState[Msg, Model],
    traceOperation: RuntimeTraceOperation
  ): Task[Diff] =
    for
      _ <- RuntimeTraceOperation.model(
             traceOperation,
             RuntimeTraceStage.ModelProposed,
             "Handler proposed a model",
             model
           )
      currentUrl <- state.currentUrlRef.get
      _          <- RuntimeTraceOperation.event(
             traceOperation,
             RuntimeTraceStage.RenderStarted,
             "Render started"
           )
      nextSignalRevision = rendered.signalRevision + 1L
      nextEvaluation <- SocketComponentRuntime.evaluateViewGraph(
                          state.viewGraph,
                          model -> currentUrl,
                          rendered.signalEvaluation,
                          nextSignalRevision,
                          state.componentsRef,
                          state.ctx,
                          manageNestedRender = false
                        )
      _ <- RuntimeTraceOperation.model(
             traceOperation,
             RuntimeTraceStage.ModelRendered,
             "Proposed model rendered",
             model
           )
      _ <- RuntimeTraceOperation.event(
             traceOperation,
             RuntimeTraceStage.RenderCompleted,
             "Render completed"
           )
      nextCompiled = nextEvaluation.compiled
      diff         = TreeDiff.diff(rendered.compiled, nextCompiled)
      _ <- RuntimeTraceOperation.event(
             traceOperation,
             RuntimeTraceStage.TreeDiff,
             if diff.isEmpty then "Tree diff is empty" else "Tree diff contains changes"
           )
      nextPageTitle =
        Option.when(state.ownsPageTitle)(normalizePageTitle(state.lv.pageTitle(model))).flatten
      nextRendered = RenderedView(
                       compiled = nextCompiled,
                       bindings = BindingRegistry.collect[Any](nextCompiled),
                       pageTitle = nextPageTitle,
                       signalEvaluation = nextEvaluation.evaluation,
                       signalRevision = nextSignalRevision
                     )
      _      <- state.ctx.hooks.runAfterRender[Msg, Model](model, state.ctx)
      events <- SocketClientEventRuntime.drain(state.clientEventsRef)
      _      <- SocketStreamRuntime.prune(state.streamRef)
      titleUpdate = Option.when(
                      state.ownsPageTitle && nextPageTitle != rendered.pageTitle
                    )(nextPageTitle.getOrElse(""))
      renderedDiff = withTitle(withClientEvents(diff, events), titleUpdate)
      _ <- state.componentCidsRef.update(
             _ ++ (
               renderedDiff match
                 case Diff.Tag(_, _, _, _, _, components, _, _) => components.keySet
                 case _                                         => Set.empty[Int]
             )
           )
      _ <- state.ref.set((model, nextRendered))
      _ <- state.pendingNavigationModelRef.set(None)
      _ <- RuntimeTraceOperation.model(
             traceOperation,
             RuntimeTraceStage.ModelCommitted,
             "Rendered model committed",
             model
           )
    yield renderedDiff

  def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    val operation = RuntimeTraceOperation.resolve(
      state.runtimeTrace,
      meta,
      RuntimeTraceOperationKind.Other
    )
    val tracedMeta = RuntimeTraceOperation.attach(meta, operation)
    RuntimeTraceOperation.event(
      operation,
      RuntimeTraceStage.FinalPayload,
      "Final socket payload published"
    ) *> state.outQueue.offer(payload -> tracedMeta).unit

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

  private[scalive] def withTitle(diff: Diff, title: Option[String]): Diff =
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
