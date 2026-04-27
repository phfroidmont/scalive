package scalive
package socket

import zio.*

import scalive.*
import scalive.Mod.Attr
import scalive.Mod.Content

final private[scalive] case class ComponentInstance(
  cid: Int,
  identity: ComponentIdentity,
  component: LiveComponent[Any, Any, Any],
  model: Any)

final private[scalive] case class ComponentRuntimeState(
  instances: Map[ComponentIdentity, ComponentInstance],
  byCid: Map[Int, ComponentIdentity],
  pendingUpdates: Map[ComponentIdentity, Vector[Any]],
  nextCid: Int):
  def instance(cid: Int): Option[ComponentInstance] =
    byCid.get(cid).flatMap(instances.get)

  def removeCids(cids: Set[Int]): ComponentRuntimeState =
    val identities = cids.flatMap(byCid.get)
    copy(
      instances = instances -- identities,
      byCid = byCid -- cids,
      pendingUpdates = pendingUpdates -- identities
    )

private[scalive] object ComponentRuntimeState:
  val empty: ComponentRuntimeState = ComponentRuntimeState(Map.empty, Map.empty, Map.empty, 1)

final private[scalive] class SocketComponentUpdateRuntime(ref: Ref[ComponentRuntimeState])
    extends ComponentUpdateRuntime:
  def sendUpdate[Props](
    componentClass: Class[?],
    id: String,
    props: Props
  ): UIO[Unit] =
    val identity = ComponentIdentity(componentClass, id)
    ref.update { state =>
      val pending = state.pendingUpdates.getOrElse(identity, Vector.empty) :+ props
      state.copy(pendingUpdates = state.pendingUpdates.updated(identity, pending))
    }

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
      _        <- componentsRef.set(cursor.state)
    yield rendered

  def handleComponentMessage[Msg, Model](
    cid: Int,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentMessage(cid, message, rendered, meta, state, ComponentResponseMode.EventReply)

  def handleComponentServerMessage[Msg, Model](
    cid: Int,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    handleComponentMessage(cid, message, rendered, meta, state, ComponentResponseMode.ServerDiff)

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
                     yield true
    yield handled

  private def handleComponentMessage[Msg, Model](
    cid: Int,
    message: Any,
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
                       componentCtx = componentContext(state.ctx, cid)
                       (model, navigation) <-
                         SocketModelRuntime.captureNavigation(state)(
                           LiveIO
                             .toZIO(
                               instance.component.handleMessage(instance.model)(message)
                             )
                             .provide(ZLayer.succeed(componentCtx))
                         )
                       _ <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = model)
                              current.copy(instances =
                                current.instances.updated(instance.identity, updated)
                              )
                            }
                       (parentModel, _) <- state.ref.get
                       _                <- navigation match
                              case Some(command) =>
                                eventReply(responseMode, meta, state) *>
                                  state.patchRedirectCountRef.set(0) *>
                                  SocketInbound.handleNavigationCommand(
                                    rendered,
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
                                            state
                                          )
                                  _ <- SocketModelRuntime.publishPayload(
                                         diffPayload(responseMode, diff),
                                         meta,
                                         state
                                       )
                                yield ()
                     yield true
    yield handled

  def handleComponentTargetMessage[Msg, Model](
    componentClass: Class[?],
    cid: Int,
    message: Any,
    rendered: RenderedView,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Boolean] =
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case Some(instance) if instance.identity.componentClass == componentClass =>
                     handleComponentMessage(cid, message, rendered, meta, state)
                   case _ =>
                     ZIO.succeed(false)
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
          renderedAll     <- ZIO.foreach(allEntries)(entries =>
                           ZIO.foreach(entries)(entry => renderKeyedEntry(entry, cursor, ctx))
                         )
        yield Content.Keyed(renderedEntries, stream, renderedAll)

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
    val typed        = spec.asInstanceOf[LiveComponentSpec[Any, Any, Any]]
    val identity     = ComponentIdentity(typed.component.getClass, typed.id)
    val duplicated   = cursor.renderedIdentities.contains(identity)
    val existing     = cursor.state.instances.get(identity)
    val cid          = existing.map(_.cid).getOrElse(cursor.state.nextCid)
    val component    = existing.map(_.component).getOrElse(typed.component)
    val componentCtx = componentContext(ctx, cid)
    val mounted      = existing match
      case Some(instance) => ZIO.succeed(instance.model)
      case None => LiveIO.toZIO(component.mount(typed.props)).provide(ZLayer.succeed(componentCtx))
    val updateProps =
      cursor.state.pendingUpdates.get(identity).flatMap(_.lastOption).getOrElse(typed.props)

    for
      _ <-
        if duplicated then
          ZIO.fail(
            new IllegalArgumentException(
              s"Duplicate live component id '${typed.id}' for ${typed.component.getClass.getName}"
            )
          )
        else ZIO.succeed(cursor.renderedIdentities = cursor.renderedIdentities + identity)
      model        <- mounted
      updatedModel <- LiveIO
                        .toZIO(component.update(updateProps, model))
                        .provide(ZLayer.succeed(componentCtx))
      instance = ComponentInstance(cid, identity, component, updatedModel)
      _        = cursor.state = cursor.state.copy(
            instances = cursor.state.instances.updated(identity, instance),
            byCid = cursor.state.byCid.updated(cid, identity),
            pendingUpdates = cursor.state.pendingUpdates.removed(identity),
            nextCid = if existing.isDefined then cursor.state.nextCid else cursor.state.nextCid + 1
          )
      ref = ComponentRef[Any](cid)
      rendered <- renderElement(component.render(updatedModel, ref), cursor, componentCtx)
      wrapped  <- wrapComponentMessages(cid, rendered.prepended(phx.component := cid.toString))
    yield Content.Component(cid, wrapped)
  end renderComponent

  private enum ComponentResponseMode:
    case EventReply
    case ServerDiff

  private def componentContext(ctx: LiveContext, cid: Int): LiveContext =
    ctx.copy(
      streams = SocketStreamRuntime.scoped(ctx.streams, SocketStreamRuntime.componentScope(cid)),
      async = SocketAsyncRuntime.scoped(ctx.async, LiveAsyncOwner.Component(cid))
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
            idAttr       := registration.topic.stripPrefix("lv:"),
            phx.session  := registration.session,
            phx.parentId := registration.parentTopic,
            phx.childId  := registration.id,
            phx.sticky   := registration.sticky,
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
