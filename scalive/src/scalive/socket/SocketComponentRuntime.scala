package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object SocketComponentRuntime:
  def renderRoot[Msg, Model](
    root: HtmlElement[Msg],
    state: RuntimeState[Msg, Model]
  ): Task[HtmlElement[Any]] =
    renderRoot(root, state.componentsRef, state.ctx)

  def renderRoot[Msg](
    root: HtmlElement[Msg],
    componentsRef: Ref[ComponentRuntimeState],
    ctx: LiveContext
  ): Task[HtmlElement[Any]] =
    for
      initial <- componentsRef.get
      cursor = ComponentCursor(initial)
      rendered <- renderElement(root, cursor, ctx)
      _        <- ctx.nestedLiveViews.afterParentRender
      _        <- componentsRef.set(cursor.state)
    yield rendered

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
                       (parentModel, _) <- state.ref.get
                       diff             <- SocketModelRuntime.updateModelAndSubscriptions(
                                 rendered,
                                 parentModel,
                                 state
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
    handleComponentAsync(cid, name, Some(message), None, rendered, meta, state)

  def handleComponentAsyncFailure[Msg, Model](
    cid: Int,
    name: String,
    cause: Throwable,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentAsync(cid, name, None, Some(cause), rendered, meta, state)

  private def handleComponentAsync[Msg, Model](
    cid: Int,
    name: String,
    message: Option[Any],
    cause: Option[Throwable],
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
                       componentCtx = componentContext(state.ctx, cid, hooksRef)
                       asyncEvent   = LiveAsyncEvent(
                                      name,
                                      message match
                                        case Some(value) => LiveAsyncResult.Succeeded(value)
                                        case None        => LiveAsyncResult.Failed(cause.get)
                                    )
                       (result, navigation) <-
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
                       (parentModel, _) <- state.ref.get
                       _                <- handleComponentLifecycleResult(
                              result,
                              navigation,
                              ComponentResponseMode.ServerDiff,
                              rendered,
                              parentModel,
                              meta,
                              state
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
                       hooksRef <- Ref.make(instance.hooks)
                       componentCtx = componentContext(state.ctx, cid, hooksRef)
                       (result, navigation) <-
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
                       (parentModel, _) <- state.ref.get
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
          diff <- SocketModelRuntime.updateModelAndSubscriptions(rendered, parentModel, state)
          _    <- SocketModelRuntime.publishPayload(
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
              diff <- SocketModelRuntime.updateModelAndSubscriptions(rendered, parentModel, state)
              _    <- SocketModelRuntime.publishPayload(
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
                       componentCtx = componentContext(state.ctx, cid, hooksRef)
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
                       (parentModel, _) <- state.ref.get
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

  private def renderElement[Msg, Model](
    element: HtmlElement[Msg],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[HtmlElement[Any]] =
    ZIO.foreach(element.mods)(renderMod(_, cursor, ctx)).map(mods => HtmlElement(element.tag, mods))

  private def renderMod[Msg, Model](
    mod: Mod[Msg],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Mod[Any]] =
    mod match
      case attr: Attr[Msg]            => ZIO.succeed(attr.asInstanceOf[Mod[Any]])
      case Content.Text(text, raw)    => ZIO.succeed(Content.Text(text, raw))
      case Content.Tag(el)            => renderElement(el, cursor, ctx).map(Content.Tag(_))
      case Content.Component(cid, el) =>
        renderElement(el, cursor, ctx).map(rendered => Content.Component(cid, rendered))
      case Content.LiveComponent(spec)                => renderComponent(spec, cursor, ctx)
      case Content.LiveView(spec)                     => renderLiveView(spec, cursor, ctx)
      case Content.Flash(kind, f)                     => renderFlash(kind, f, cursor, ctx)
      case Content.Keyed(entries, stream, allEntries) =>
        for
          renderedEntries <- ZIO.foreach(entries)(entry => renderKeyedEntry(entry, cursor, ctx))
          renderedAll     <- renderStreamSnapshotEntries(allEntries, cursor, ctx)
        yield Content.Keyed(renderedEntries, stream, renderedAll)

  private def renderStreamSnapshotEntries[Msg](
    allEntries: Option[Vector[Content.Keyed.Entry[Msg]]],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Option[Vector[Content.Keyed.Entry[Any]]]] =
    allEntries match
      case None          => ZIO.none
      case Some(entries) =>
        val renderedLiveViewIds = cursor.renderedLiveViewIds
        for
          _        <- ZIO.succeed(cursor.renderedLiveViewIds = Set.empty)
          rendered <- ZIO.foreach(entries)(entry => renderKeyedEntry(entry, cursor, ctx))
          _        <- ZIO.succeed(cursor.renderedLiveViewIds = renderedLiveViewIds)
        yield Some(rendered)

  private def renderKeyedEntry[Msg](
    entry: Content.Keyed.Entry[Msg],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Content.Keyed.Entry[Any]] =
    renderElement(entry.element, cursor, ctx).map(rendered =>
      Content.Keyed.Entry(entry.key, rendered)
    )

  private def renderComponent(
    spec: LiveComponentSpec[?, ?, ?],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Content.Component[Any]] =
    val typed          = spec.asInstanceOf[LiveComponentSpec[Any, Any, Any]]
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
      hooksRef <-
        Ref.make(
          existing.map(_.hooks).getOrElse(LiveHookRuntimeState.component(typed.component.hooks))
        )
      componentCtx = componentContext(ctx, cid, hooksRef)
      model <- existing match
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
            model,
            componentCtx.componentUpdateContext[Any, Any, Any]
          )
        else ZIO.succeed(model)
      hooks <- hooksRef.get
      renderProps =
        if shouldUpdate then updateProps
        else existing.map(_.props).getOrElse(updateProps)
      instance = ComponentInstance(
                   cid,
                   identity,
                   component,
                   renderProps,
                   typed.props,
                   updatedModel,
                   hooks
                 )
      _ = cursor.state = cursor.state.copy(
            instances = cursor.state.instances.updated(identity, instance),
            byCid = cursor.state.byCid.updated(cid, identity),
            pendingUpdates = cursor.state.pendingUpdates.removed(identity),
            nextCid = if existing.isDefined then cursor.state.nextCid else cursor.state.nextCid + 1
          )
      ref = ComponentRef[Any](cid)
      rendered <-
        renderElement(component.render(renderProps, updatedModel, ref), cursor, componentCtx)
      afterRenderModel <- componentCtx.hooks.runComponentAfterRender(
                            updateProps,
                            updatedModel,
                            componentCtx
                          )
      afterRenderHooks <- hooksRef.get
      _ = cursor.state = cursor.state.copy(instances =
            cursor.state.instances.updated(
              identity,
              instance.copy(model = afterRenderModel, hooks = afterRenderHooks)
            )
          )
      wrapped <- wrapComponentMessages(cid, rendered.prepended(phx.component := cid.toString))
    yield Content.Component(cid, wrapped)
    end for
  end renderComponent

  private enum ComponentResponseMode:
    case EventReply
    case ServerDiff

  private def componentContext(
    ctx: LiveContext,
    cid: Int,
    hooksRef: Ref[LiveHookRuntimeState]
  ): LiveContext =
    ctx.copy(
      uploads = SocketUploadRuntime.scoped(ctx.uploads, SocketStreamRuntime.componentScope(cid)),
      streams = SocketStreamRuntime.scoped(ctx.streams, SocketStreamRuntime.componentScope(cid)),
      async = SocketAsyncRuntime.scoped(ctx.async, LiveAsyncOwner.Component(cid)),
      hooks = new ComponentLiveHookRuntime(hooksRef)
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
    if cursor.renderedLiveViewIds.contains(spec.id) then
      ZIO.fail(new IllegalArgumentException(s"Duplicate nested LiveView id '${spec.id}'"))
    else
      cursor.renderedLiveViewIds = cursor.renderedLiveViewIds + spec.id
      ctx.nestedLiveViews.register(spec).map { registration =>
        Content.Tag(
          div(
            idAttr      := registration.id,
            phx.session := registration.session,
            Option.unless(registration.sticky)(phx.parentId := registration.parentDomId),
            Option.unless(registration.sticky)(phx.childId  := registration.id),
            phx.sticky := registration.sticky,
            registration.rendered.map(Content.Tag(_))
          )
        )
      }

  private def renderFlash(
    kind: String,
    f: String => HtmlElement[Nothing],
    cursor: ComponentCursor,
    ctx: LiveContext
  ): Task[Mod[Any]] =
    ctx.flash.get(kind).flatMap {
      case Some(message) => renderElement(f(message), cursor, ctx).map(Content.Tag(_))
      case None          => ZIO.succeed(Content.Text(""))
    }

  private def wrapComponentMessages(cid: Int, element: HtmlElement[Any]): Task[HtmlElement[Any]] =
    ZIO.foreach(element.mods)(wrapComponentMod(cid, _)).map(mods => HtmlElement(element.tag, mods))

  private def wrapComponentMod(cid: Int, mod: Mod[Any]): Task[Mod[Any]] =
    mod match
      case Attr.Binding(name, f) =>
        ZIO.succeed(Attr.Binding(name, payload => ComponentMessage(cid, f(payload))))
      case Attr.FormBinding(name, f) =>
        ZIO.succeed(Attr.FormBinding(name, data => ComponentMessage(cid, f(data))))
      case Attr.FormEventBinding(name, codec, f) =>
        val wrapped = f.asInstanceOf[FormEvent[Any] => Any]
        ZIO.succeed(
          Attr.FormEventBinding(
            name,
            codec.asInstanceOf[FormCodec[Any]],
            event => ComponentMessage(cid, wrapped(event))
          )
        )
      case Attr.JsBinding(_, _) =>
        mod match
          case Attr.JsBinding(name, command) =>
            ZIO.succeed(
              Attr.JsBinding(name, command.map(message => ComponentMessage(cid, message)))
            )
          case _ => ZIO.succeed(mod)
      case attr: Attr[Any] =>
        ZIO.succeed(attr)
      case Content.Text(_, _) =>
        ZIO.succeed(mod)
      case Content.Tag(el) =>
        wrapComponentMessages(cid, el).map(Content.Tag(_))
      case Content.Component(_, _) =>
        ZIO.succeed(mod)
      case Content.LiveComponent(_) =>
        ZIO.fail(
          new IllegalStateException("nested live components must be resolved before wrapping")
        )
      case Content.LiveView(_) =>
        ZIO.fail(new IllegalStateException("nested LiveViews must be resolved before wrapping"))
      case Content.Flash(_, _) =>
        ZIO.fail(new IllegalStateException("flash content must be resolved before wrapping"))
      case Content.Keyed(entries, stream, allEntries) =>
        for
          renderedEntries <- ZIO.foreach(entries)(entry =>
                               wrapComponentMessages(cid, entry.element)
                                 .map(rendered => Content.Keyed.Entry(entry.key, rendered))
                             )
          renderedAll <- ZIO.foreach(allEntries)(entries =>
                           ZIO.foreach(entries)(entry =>
                             wrapComponentMessages(cid, entry.element)
                               .map(rendered => Content.Keyed.Entry(entry.key, rendered))
                           )
                         )
        yield Content.Keyed(renderedEntries, stream, renderedAll)
end SocketComponentRuntime
