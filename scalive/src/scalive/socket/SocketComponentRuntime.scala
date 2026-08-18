package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.Mod.Content

private[scalive] object SocketComponentRuntime:
  def evaluateViewGraph[Model](
    graph: ViewGraph.Root[Model],
    model: Model,
    previous: SignalEvaluation,
    revision: Long,
    componentsRef: Ref[ComponentRuntimeState],
    ctx: LiveContext,
    manageNestedRender: Boolean = true
  ): Task[ViewGraph.Evaluated] =
    val render =
      for
        initial <- componentsRef.get
        cursor   = ComponentCursor(initial)
        resolver = viewGraphResolver(cursor, ctx)
        evaluated <- graph.evaluateZIO(model, previous, revision, resolver)
        _         <- componentsRef.set(cursor.state)
      yield evaluated
    if manageNestedRender then ctx.nestedLiveViews.renderTransaction(render) else render

  private def viewGraphResolver(
    cursor: ComponentCursor,
    ctx: LiveContext
  ): ViewGraph.Resolver =
    new ViewGraph.Resolver:
      def component(
        spec: LiveComponentSpec[?, ?, ?, ?],
        path: BindingId.Path,
        transaction: SignalEvaluation.Transaction
      ): Task[ViewGraph.ResolvedContent] =
        val typed = spec.asInstanceOf[LiveComponentSpec[Any, Any, Any, Any]]
        resolveViewGraphComponent(typed, path, cursor, ctx, transaction)

      def liveView(
        spec: NestedLiveViewSpec[?, ?],
        path: BindingId.Path
      ): Task[ViewGraph.ResolvedContent] =
        val _ = path
        renderLiveView(spec, cursor, ctx).map(compileResolved)

      def flash(kind: String): Task[Option[String]] =
        ctx.flash.get(kind)

      private def compileResolved(mod: Mod[Any]): ViewGraph.ResolvedContent =
        val wrapper  = ctx.csrfToken.fold(div(mod))(CsrfProtection.injectForms(div(mod), _))
        val compiled = RenderSnapshot.compile(wrapper)
        ViewGraph.ResolvedContent(
          compiled.root.slots.headOption.getOrElse(RenderSnapshot.StringSlot("")),
          compiled.bindings,
          compiled.trackedStaticUrls
        )

  def handleComponentMessage[Msg, Model](
    cid: Int,
    message: Any,
    event: LiveEvent,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentMessage(
      cid,
      message,
      Some(event),
      rendered,
      meta,
      state,
      ComponentResponseMode.EventReply
    )

  def handleComponentServerMessage[Msg, Model](
    cid: Int,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentMessage(
      cid,
      message,
      None,
      rendered,
      meta,
      state,
      ComponentResponseMode.ServerDiff
    )

  def handleComponentAssign[Msg, Model](
    cid: Int,
    update: Any => Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case None           => ZIO.succeed(false)
                   case Some(instance) =>
                     for
                       updatedModel <- ZIO.attempt(update(instance.model))
                       _            <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = updatedModel)
                              current.copy(instances =
                                current.instances.updated(instance.identity, updated)
                              )
                            }
                       (parentModel, _) <- SocketModelRuntime.currentModelAndRendered(state)
                       diff             <- SocketModelRuntime.updateModelAndSubscriptions(
                                 rendered,
                                 parentModel,
                                 state,
                                 meta.traceOperation
                               )
                       _ <- SocketModelRuntime.publishPayload(
                              WebSocketMessage.Payload.Diff(diff),
                              meta,
                              state
                            )
                       _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                     yield true
    yield handled

  def handleComponentAsyncSuccess[Msg, Model](
    cid: Int,
    name: String,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentAsync(
      cid,
      name,
      Some(message),
      LiveAsyncResult.Succeeded(message),
      rendered,
      meta,
      state
    )

  def handleComponentAsyncFailure[Msg, Model](
    cid: Int,
    name: String,
    cause: Throwable,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentAsync(
      cid,
      name,
      Some(message),
      LiveAsyncResult.Failed(cause),
      rendered,
      meta,
      state
    )

  def handleComponentAsyncCancelled[Msg, Model](
    cid: Int,
    name: String,
    reason: Option[String],
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentAsync(
      cid,
      name,
      Some(message),
      LiveAsyncResult.Cancelled(reason),
      rendered,
      meta,
      state
    )

  def handleComponentAsyncMappingFailure[Msg, Model](
    cid: Int,
    name: String,
    cause: Throwable,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    ZIO.logErrorCause(
      s"Component async task '$name' could not map its result to a message",
      Cause.fail(cause)
    ) *>
      handleComponentAsync(
        cid,
        name,
        None,
        LiveAsyncResult.Failed(cause),
        rendered,
        meta,
        state
      )

  private def handleComponentAsync[Msg, Model](
    cid: Int,
    name: String,
    message: Option[Any],
    result: LiveAsyncResult[Any],
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case None           => ZIO.succeed(false)
                   case Some(instance) =>
                     for
                       _ <- RuntimeTraceOperation.message(
                              meta.traceOperation,
                              RuntimeTraceStage.TypedMessage,
                              "Component received a typed message",
                              message
                            )
                       _ <- RuntimeTraceOperation.event(
                              meta.traceOperation,
                              RuntimeTraceStage.LifecycleStarted,
                              "Component lifecycle and handler started"
                            )
                       hooksRef <- Ref.make(instance.hooks)
                       componentCtx = componentContext(
                                        state.ctx,
                                        cid,
                                        instance.identity,
                                        hooksRef,
                                        instance.outputMapper,
                                        instance.outputOwner
                                      )
                       asyncEvent = LiveAsyncEvent(AsyncKey[Any](name), result)
                       lifecycleResult <-
                         SocketModelRuntime.captureNavigation(state)(
                           componentCtx.hooks
                             .runComponentAsync(
                               instance.props,
                               instance.model,
                               asyncEvent,
                               componentCtx
                             ).flatMap {
                               case LiveHookResult.Continue(hookModel) =>
                                 message match
                                   case Some(value) =>
                                     instance.component
                                       .handleMessage(
                                         instance.props,
                                         hookModel,
                                         componentCtx.componentMessageContext[Any, Any, Any]
                                       )(value)
                                       .map(LiveEventHookResult.Continue(_))
                                   case None =>
                                     ZIO.succeed(LiveEventHookResult.Continue(hookModel))
                               case LiveHookResult.Halt(hookModel) =>
                                 ZIO.succeed(LiveEventHookResult.Halt(hookModel, None))
                             }
                         )
                       result     = lifecycleResult._1
                       navigation = lifecycleResult._2
                       _ <- RuntimeTraceOperation.event(
                              meta.traceOperation,
                              RuntimeTraceStage.LifecycleCompleted,
                              "Component lifecycle and handler completed"
                            )
                       hooks <- hooksRef.get
                       model = result match
                                 case LiveEventHookResult.Continue(value) => value
                                 case LiveEventHookResult.Halt(value, _)  => value
                       _ <- RuntimeTraceOperation.model(
                              meta.traceOperation,
                              RuntimeTraceStage.ModelProposed,
                              "Component proposed a model",
                              model
                            )
                       _ <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = model, hooks = hooks)
                              current.copy(instances =
                                current.instances.updated(instance.identity, updated)
                              )
                            }
                       (parentModel, _) <- SocketModelRuntime.currentModelAndRendered(state)
                       _                <- handleComponentLifecycleResult(
                              result,
                              navigation,
                              ComponentResponseMode.ServerDiff,
                              rendered,
                              parentModel,
                              meta,
                              state
                            )
                       _ <- RuntimeTraceOperation.model(
                              meta.traceOperation,
                              RuntimeTraceStage.ModelCommitted,
                              "Component model committed",
                              model
                            )
                     yield true
    yield handled

  private def handleComponentMessage[Msg, Model](
    cid: Int,
    message: Any,
    event: Option[LiveEvent],
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model],
    responseMode: ComponentResponseMode
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case None           => ZIO.succeed(false)
                   case Some(instance) =>
                     for
                       _ <- RuntimeTraceOperation.message(
                              meta.traceOperation,
                              RuntimeTraceStage.TypedMessage,
                              "Component received a typed message",
                              message
                            )
                       _ <- RuntimeTraceOperation.event(
                              meta.traceOperation,
                              RuntimeTraceStage.LifecycleStarted,
                              "Component lifecycle and handler started"
                            )
                       hooksRef <- Ref.make(instance.hooks)
                       componentCtx = componentContext(
                                        state.ctx,
                                        cid,
                                        instance.identity,
                                        hooksRef,
                                        instance.outputMapper,
                                        instance.outputOwner
                                      )
                       lifecycleResult <-
                         SocketModelRuntime.captureNavigation(state)(
                           runComponentEventHooks(instance, message, event, componentCtx).flatMap {
                             case LiveEventHookResult.Continue(hookModel) =>
                               instance.component
                                 .handleMessage(
                                   instance.props,
                                   hookModel,
                                   componentCtx.componentMessageContext[Any, Any, Any]
                                 )(message)
                                 .map(LiveEventHookResult.Continue(_))
                             case halt @ LiveEventHookResult.Halt(_, _) => ZIO.succeed(halt)
                           }
                         )
                       result     = lifecycleResult._1
                       navigation = lifecycleResult._2
                       _ <- RuntimeTraceOperation.event(
                              meta.traceOperation,
                              RuntimeTraceStage.LifecycleCompleted,
                              "Component lifecycle and handler completed"
                            )
                       hooks <- hooksRef.get
                       model = result match
                                 case LiveEventHookResult.Continue(value) => value
                                 case LiveEventHookResult.Halt(value, _)  => value
                       _ <- RuntimeTraceOperation.model(
                              meta.traceOperation,
                              RuntimeTraceStage.ModelProposed,
                              "Component proposed a model",
                              model
                            )
                       _ <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = model, hooks = hooks)
                              current.copy(instances =
                                current.instances.updated(instance.identity, updated)
                              )
                            }
                       _ <- RuntimeTraceOperation.model(
                              meta.traceOperation,
                              RuntimeTraceStage.ModelCommitted,
                              "Component model committed",
                              model
                            )
                       (parentModel, _) <- SocketModelRuntime.currentModelAndRendered(state)
                       _                <- handleComponentLifecycleResult(
                              result,
                              navigation,
                              responseMode,
                              rendered,
                              parentModel,
                              meta,
                              state
                            )
                     yield true
    yield handled

  private def runComponentEventHooks(
    instance: ComponentInstance,
    message: Any,
    event: Option[LiveEvent],
    componentCtx: LiveContext
  ): Task[LiveEventHookResult[Any]] =
    event match
      case Some(value) =>
        componentCtx.hooks
          .runComponentRawEvent(instance.props, instance.model, value, componentCtx)
          .flatMap {
            case LiveEventHookResult.Continue(rawModel) =>
              componentCtx.hooks.runComponentEvent(
                instance.props,
                rawModel,
                message,
                value,
                componentCtx
              )
            case halt @ LiveEventHookResult.Halt(_, _) => ZIO.succeed(halt)
          }
      case None =>
        ZIO.succeed(LiveEventHookResult.Continue(instance.model))

  private def handleComponentLifecycleResult[Msg, Model](
    result: LiveEventHookResult[Any],
    navigation: Option[LiveNavigationCommand],
    responseMode: ComponentResponseMode,
    rendered: RenderedView,
    parentModel: Model,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    result match
      case LiveEventHookResult.Halt(_, reply) =>
        for
          diff <- SocketModelRuntime.updateModelAndSubscriptions(
                    rendered,
                    parentModel,
                    state,
                    meta.traceOperation
                  )
          _ <- SocketModelRuntime.publishPayload(
                 componentHaltPayload(responseMode, reply, diff),
                 meta,
                 state
               )
          _ <- navigation match
                 case Some(command) =>
                   state.patchRedirectCountRef.set(0) *>
                     SocketInbound.handleNavigationCommand(
                       parentModel,
                       command,
                       meta,
                       state
                     )
                 case None => SocketFlashRuntime.resetNavigation(state.flashRef)
        yield ()
      case LiveEventHookResult.Continue(_) =>
        navigation match
          case Some(command) =>
            eventReply(responseMode, meta, state) *>
              state.patchRedirectCountRef.set(0) *>
              SocketInbound.handleNavigationCommand(
                parentModel,
                command,
                meta,
                state
              )
          case None =>
            for
              diff <- SocketModelRuntime.updateModelAndSubscriptions(
                        rendered,
                        parentModel,
                        state,
                        meta.traceOperation
                      )
              _ <- SocketModelRuntime.publishPayload(
                     diffPayload(responseMode, diff),
                     meta,
                     state
                   )
              _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
            yield ()

  private def componentHaltPayload(
    responseMode: ComponentResponseMode,
    reply: Option[Json],
    diff: Diff
  ): WebSocketMessage.Payload =
    responseMode match
      case ComponentResponseMode.EventReply =>
        reply match
          case Some(replyValue) =>
            WebSocketMessage.Payload.okReply(
              WebSocketMessage.LiveResponse.InterceptReply(
                replyValue,
                Option.when(!diff.isEmpty)(diff)
              )
            )
          case None if !diff.isEmpty =>
            WebSocketMessage.Payload.okReply(WebSocketMessage.LiveResponse.Diff(diff))
          case None =>
            WebSocketMessage.Payload.okReply(WebSocketMessage.LiveResponse.Empty)
      case ComponentResponseMode.ServerDiff =>
        WebSocketMessage.Payload.Diff(diff)

  def handleComponentTargetMessage[Msg, Model](
    componentClass: Class[?],
    cid: Int,
    message: Any,
    event: LiveEvent,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case Some(instance) if instance.identity.componentClass == componentClass =>
                     handleComponentMessage(cid, message, event, rendered, meta, state)
                   case _ =>
                     ZIO.succeed(false)
    yield handled

  def handleComponentInstanceMessage[Msg, Model](
    identity: ComponentIdentity,
    message: Any,
    event: LiveEvent,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instances.get(identity) match
                   case Some(instance) =>
                     handleComponentMessage(
                       instance.cid,
                       message,
                       event.copy(cid = Some(instance.cid)),
                       rendered,
                       meta,
                       state
                     )
                   case None => ZIO.succeed(false)
    yield handled

  def handleComponentRawEvent[Msg, Model](
    cid: Int,
    event: LiveEvent,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case None           => ZIO.succeed(false)
                   case Some(instance) =>
                     for
                       hooksRef <- Ref.make(instance.hooks)
                       componentCtx = componentContext(
                                        state.ctx,
                                        cid,
                                        instance.identity,
                                        hooksRef,
                                        instance.outputMapper,
                                        instance.outputOwner
                                      )
                       (result, navigation) <-
                         SocketModelRuntime.captureNavigation(state)(
                           componentCtx.hooks.runComponentRawEvent(
                             instance.props,
                             instance.model,
                             event,
                             componentCtx
                           )
                         )
                       hooks <- hooksRef.get
                       model = result match
                                 case LiveEventHookResult.Continue(value) => value
                                 case LiveEventHookResult.Halt(value, _)  => value
                       _ <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = model, hooks = hooks)
                              current.copy(instances =
                                current.instances.updated(instance.identity, updated)
                              )
                            }
                       (parentModel, _) <- SocketModelRuntime.currentModelAndRendered(state)
                       _                <- handleComponentLifecycleResult(
                              result,
                              navigation,
                              ComponentResponseMode.EventReply,
                              rendered,
                              parentModel,
                              meta,
                              state
                            )
                     yield true
    yield handled

  final private class ComponentCursor(
    var state: ComponentRuntimeState,
    var renderedIdentities: Set[ComponentIdentity] = Set.empty,
    var renderedLiveViewIds: Set[String] = Set.empty)

  private def resolveViewGraphComponent(
    typed: LiveComponentSpec[Any, Any, Any, Any],
    path: BindingId.Path,
    cursor: ComponentCursor,
    ctx: LiveContext,
    parentTransaction: SignalEvaluation.Transaction
  ): Task[ViewGraph.ResolvedContent] =
    val identity       = ComponentIdentity(typed.component.getClass, typed.id)
    val duplicated     = cursor.renderedIdentities.contains(identity)
    val existing       = cursor.state.instances.get(identity)
    val cid            = existing.map(_.cid).getOrElse(cursor.state.nextCid)
    val component      = existing.map(_.component).getOrElse(typed.component)
    val pendingUpdates = cursor.state.pendingUpdates.getOrElse(identity, Vector.empty)
    val updateProps    = pendingUpdates.lastOption.getOrElse(typed.props)

    for
      _ <-
        if duplicated then
          ZIO.fail(
            new IllegalArgumentException(
              s"Duplicate live component id '${typed.id}' for ${typed.component.getClass.getName}"
            )
          )
        else ZIO.succeed(cursor.renderedIdentities = cursor.renderedIdentities + identity)
      _ = if existing.isEmpty then
            cursor.state = cursor.state.copy(nextCid = cursor.state.nextCid + 1)
      hooksRef <- Ref.make(
                    existing.map(_.hooks).getOrElse(LiveHookRuntimeState.component(component.hooks))
                  )
      outputOwner  = ctx.componentOutputOwner
      outputMapper = typed.outputMapper.orElse(existing.flatMap(_.outputMapper))
      componentCtx = componentContext(ctx, cid, identity, hooksRef, outputMapper, outputOwner)
      mountedModel <- existing match
                        case Some(instance) => ZIO.succeed(instance.model)
                        case None           =>
                          component.mount(
                            typed.props,
                            componentCtx.componentMountContext[Any, Any, Any]
                          )
      shouldUpdate = existing.isEmpty || pendingUpdates.nonEmpty || existing.exists(
                       _.parentProps != typed.props
                     )
      updatedModel <-
        if shouldUpdate then
          component.update(
            updateProps,
            mountedModel,
            componentCtx.componentUpdateContext[Any, Any, Any]
          )
        else ZIO.succeed(mountedModel)
      renderProps =
        if shouldUpdate then updateProps
        else existing.map(_.props).getOrElse(updateProps)
      ref   = ComponentRef[Any](cid)
      graph = existing
                .map(_.viewGraph).getOrElse(
                  ViewGraph.buildComponent[Any, Any](
                    (props, model) =>
                      component.view(props, model, ref).prepended(phx.component := cid.toString),
                    BindingId.childComponentPath(path, 0, cid)
                  )
                )
      _        = if existing.isEmpty then parentTransaction.onRollback(graph.dispose())
      revision = existing.fold(1L)(_.signalRevision + 1L)
      evaluated <- graph.evaluateZIO(
                     renderProps,
                     updatedModel,
                     existing.fold(SignalEvaluation.empty)(_.signalEvaluation),
                     revision,
                     viewGraphResolver(cursor, componentCtx),
                     Some(parentTransaction)
                   )
      _ <- componentCtx.hooks.runComponentAfterRender(updateProps, updatedModel, componentCtx)
      afterRenderHooks <- hooksRef.get
      instance = ComponentInstance(
                   cid,
                   identity,
                   component,
                   renderProps,
                   typed.props,
                   updatedModel,
                   afterRenderHooks,
                   outputMapper,
                   outputOwner,
                   viewGraph = graph,
                   signalEvaluation = evaluated.evaluation,
                   signalRevision = revision
                 )
      _ = cursor.state = cursor.state.copy(
            instances = cursor.state.instances.updated(identity, instance),
            byCid = cursor.state.byCid.updated(cid, identity),
            pendingUpdates = cursor.state.pendingUpdates.removed(identity)
          )
      wrappedBindings = evaluated.compiled.bindings.map { case (id, handler) =>
                          id -> ((payload: BindingPayload) =>
                            handler(payload) match
                              case message: ComponentMessage         => message
                              case message: ComponentInstanceMessage => message
                              case message: ComponentTargetMessage   => message
                              case message => ComponentMessage(cid, message)
                          )
                        }
    yield ViewGraph.ResolvedContent(
      RenderSnapshot.ComponentSlot(RenderSnapshot.CompiledComponent(cid, evaluated.compiled.root)),
      wrappedBindings,
      evaluated.compiled.trackedStaticUrls
    )
    end for
  end resolveViewGraphComponent

  private enum ComponentResponseMode:
    case EventReply
    case ServerDiff

  private def componentContext(
    ctx: LiveContext,
    cid: Int,
    identity: ComponentIdentity,
    hooksRef: Ref[LiveHookRuntimeState],
    outputMapper: Option[Any => Any],
    outputOwner: ComponentOutputOwner
  ): LiveContext =
    ctx.copy(
      uploads = SocketUploadRuntime.scoped(ctx.uploads, SocketStreamRuntime.componentScope(cid)),
      streams = SocketStreamRuntime.scoped(ctx.streams, SocketStreamRuntime.componentScope(cid)),
      async = SocketAsyncRuntime.scoped(ctx.async, LiveAsyncOwner.Component(cid)),
      hooks = new ComponentLiveHookRuntime(hooksRef),
      componentOutput = outputMapper.fold[ComponentOutputRuntime](ComponentOutputRuntime.Disabled)(
        ctx.componentOutput.scoped(outputOwner, identity, _)
      ),
      componentOutputOwner = ComponentOutputOwner.Component(cid)
    )

  private def eventReply[Msg, Model](
    responseMode: ComponentResponseMode,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    responseMode match
      case ComponentResponseMode.EventReply =>
        SocketModelRuntime.publishPayload(
          WebSocketMessage.Payload.okReply(WebSocketMessage.LiveResponse.Empty),
          meta,
          state
        )
      case ComponentResponseMode.ServerDiff => ZIO.unit

  private def diffPayload(
    responseMode: ComponentResponseMode,
    diff: Diff
  ): WebSocketMessage.Payload =
    responseMode match
      case ComponentResponseMode.EventReply =>
        WebSocketMessage.Payload.okReply(WebSocketMessage.LiveResponse.Diff(diff))
      case ComponentResponseMode.ServerDiff =>
        WebSocketMessage.Payload.Diff(diff)

  private def renderLiveView(
    spec: NestedLiveViewSpec[?, ?],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Content.Tag[Any]] =
    renderLiveViewElement(spec, cursor, ctx).map(Content.Tag(_))

  private def renderLiveViewElement(
    spec: NestedLiveViewSpec[?, ?],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[HtmlElement[Any]] =
    if cursor.renderedLiveViewIds.contains(spec.id) then
      ZIO.fail(new IllegalArgumentException(s"Duplicate nested LiveView id '${spec.id}'"))
    else
      cursor.renderedLiveViewIds = cursor.renderedLiveViewIds + spec.id
      ctx.nestedLiveViews.register(spec).map { registration =>
        div(
          idAttr      := registration.id,
          phx.session := registration.session,
          phx.static  := registration.static,
          Option.unless(registration.sticky)(phx.parentId := registration.parentDomId),
          phx.sticky := registration.sticky,
          Option.when(registration.loading)(cls := "phx-loading"),
          registration.rendered
        )
      }

end SocketComponentRuntime
