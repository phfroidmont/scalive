package scalive
package socket

import zio.*

import scalive.*
import scalive.Mod.Attr
import scalive.Mod.Content

final private[scalive] case class ComponentKey(componentClass: Class[?], id: String)

final private[scalive] case class ComponentInstance(
  cid: Int,
  key: ComponentKey,
  component: LiveComponent[Any, Any, Any],
  model: Any)

final private[scalive] case class ComponentRuntimeState(
  instances: Map[ComponentKey, ComponentInstance],
  byCid: Map[Int, ComponentKey],
  nextCid: Int):
  def instance(cid: Int): Option[ComponentInstance] =
    byCid.get(cid).flatMap(instances.get)

private[scalive] object ComponentRuntimeState:
  val empty: ComponentRuntimeState = ComponentRuntimeState(Map.empty, Map.empty, 1)

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
    for
      runtime <- state.componentsRef.get
      handled <- runtime.instance(cid) match
                   case None           => ZIO.succeed(false)
                   case Some(instance) =>
                     for
                       (model, navigation) <-
                         SocketModelRuntime.captureNavigation(state)(
                           LiveIO
                             .toZIO(
                               instance.component.handleMessage(instance.model)(message)
                             )
                             .provide(ZLayer.succeed(state.ctx))
                         )
                       _ <- state.componentsRef.update { current =>
                              val updated = instance.copy(model = model)
                              current.copy(instances =
                                current.instances.updated(instance.key, updated)
                              )
                            }
                       (parentModel, _) <- state.ref.get
                       _                <- navigation match
                              case Some(command) =>
                                SocketModelRuntime.publishPayload(
                                  WebSocketMessage.Payload.okReply(
                                    WebSocketMessage.LiveResponse.Empty
                                  ),
                                  meta,
                                  state
                                ) *>
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
                                         WebSocketMessage.Payload.okReply(
                                           WebSocketMessage.LiveResponse.Diff(diff)
                                         ),
                                         meta,
                                         state
                                       )
                                yield ()
                     yield true
    yield handled

  final private class ComponentCursor(var state: ComponentRuntimeState)

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
    val typed     = spec.asInstanceOf[LiveComponentSpec[Any, Any, Any]]
    val key       = ComponentKey(typed.component.getClass, typed.id)
    val existing  = cursor.state.instances.get(key)
    val cid       = existing.map(_.cid).getOrElse(cursor.state.nextCid)
    val component = existing.map(_.component).getOrElse(typed.component)
    val mounted   = existing match
      case Some(instance) => ZIO.succeed(instance.model)
      case None           => LiveIO.toZIO(component.mount(typed.props)).provide(ZLayer.succeed(ctx))

    for
      model        <- mounted
      updatedModel <- LiveIO
                        .toZIO(component.update(typed.props, model))
                        .provide(ZLayer.succeed(ctx))
      instance = ComponentInstance(cid, key, component, updatedModel)
      _        = cursor.state = cursor.state.copy(
            instances = cursor.state.instances.updated(key, instance),
            byCid = cursor.state.byCid.updated(cid, key),
            nextCid = if existing.isDefined then cursor.state.nextCid else cursor.state.nextCid + 1
          )
      ref      = ComponentRef[Any](cid)
      rendered = component.render(updatedModel, ref).prepended(phx.component := cid.toString)
      wrapped <- wrapComponentMessages(cid, rendered)
    yield Content.Component(cid, wrapped)

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
      case Content.Component(componentCid, el) =>
        wrapComponentMessages(cid, el).map(rendered => Content.Component(componentCid, rendered))
      case Content.LiveComponent(_) =>
        ZIO.fail(
          new IllegalStateException("nested live components must be resolved before wrapping")
        )
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
