package scalive

extension [A](items: IterableOnce[A])
  /** Renders one element per item, identifying each row by `key`.
    *
    * This form renders a plain collection, such as a constant list of options. Keys are compared
    * with `==`.
    */
  def splitBy[Key, Msg](key: A => Key)(project: (Key, A) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.map { item =>
      val itemKey = key(item)
      Mod.Content.Keyed.Entry(itemKey, project(itemKey, item))
    }.toVector
    Mod.Content.Keyed(entries)

  /** Renders one element per item, identifying each row by its position.
    *
    * This form renders a plain collection, such as a constant list of options.
    */
  def splitByIndex[Msg](project: (Int, A) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.zipWithIndex.map { case (item, index) =>
      Mod.Content.Keyed.Entry(index, project(index, item))
    }.toVector
    Mod.Content.Keyed(entries)

extension [A, Items <: Iterable[A]](items: Signal[Items])
  /** Renders one element per item, identifying each row by `key`.
    *
    * This form follows a signal of the collection, as in
    * `model.map(_.lines).splitBy(_.product)((product, line) => li(...))`. Each row is built once
    * per key and receives a signal of its item, so adding, removing, or reordering items updates
    * only the affected rows. See [keyed rows](https://scalive.dev/learn/rendering-and-dom-updates).
    */
  def splitBy[Key, Msg](
    key: A => Key
  )(
    project: (Key, Signal[A]) => HtmlElement[Msg]
  ): Mod[Msg] =
    Mod.Content.SignalKeyed(items.map(values => values: Iterable[A]), key, project)

  /** Renders one element per item, identifying each row by its position.
    *
    * This form follows a signal of the collection. Prefer [[splitBy]] when items have a stable key,
    * so that inserting or removing an item does not change the rows after it.
    */
  def splitByIndex[Msg](project: (Int, Signal[A]) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.SignalKeyedByIndex(items.map(values => values: Iterable[A]), project)

extension [A, Id](definition: streams.LiveStreamDef[A, Id])
  /** Renders this stream's items; `project` receives each item's DOM `id`.
    *
    * The items are the ones the LiveView or component created and updated through `ctx.streams`.
    * Rendering a stream it never created fails the render. Prefer [[renderIn]], which also sets up
    * the container.
    */
  def stream[Msg](project: (String, Signal[A]) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.ManagedStream(definition, project)

  /** Renders this stream's items inside `container`.
    *
    * Scalive sets the container's `id` to the stream's name, its `phx-update`, and each item's DOM
    * `id`, as in `Activities.renderIn(ol, aria.label := "Recent activity")(activity => li(...))`.
    * See [streams](https://scalive.dev/guides/streams-and-collection-updates).
    */
  def renderIn[Msg](
    container: HtmlTag,
    mods: Mod.Input[Msg]*
  )(
    project: Signal[A] => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    val containerMods =
      Mod.flatten(mods).filterNot(mod => isAttr(mod, "id") || isAttr(mod, "phx-update"))
    val entries = definition.stream { (domId, item) =>
      val element = project(item)
      HtmlElement(
        element.tag,
        element.mods.filterNot(isAttr(_, "id")).prepended(idAttr := domId)
      )
    }
    HtmlElement(
      container,
      Vector(idAttr := definition.name, phx.update := PhxUpdate.Stream) ++ containerMods :+ entries
    )
end extension

extension [A](stream: streams.LiveStream[A])
  /** Renders a rendered stream's items; `project` receives each item's DOM `id`.
    *
    * Prefer [[renderIn]], which also sets up the container.
    */
  private[scalive] def stream[Msg](project: (String, A) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.Stream(stream, project)

  /** Renders a rendered stream's items inside `container`.
    *
    * Scalive sets the container's `id` and `phx-update` and each item's DOM `id`. See
    * [streams](https://scalive.dev/guides/streams-and-collection-updates).
    */
  private[scalive] def renderIn[Msg](
    container: HtmlTag,
    mods: Mod.Input[Msg]*
  )(
    project: A => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    val containerMods =
      Mod.flatten(mods).filterNot(mod => isAttr(mod, "id") || isAttr(mod, "phx-update"))
    val entries = stream.stream { (domId, item) =>
      val element = project(item)
      HtmlElement(
        element.tag,
        element.mods.filterNot(isAttr(_, "id")).prepended(idAttr := domId)
      )
    }
    HtmlElement(
      container,
      Vector(idAttr := stream.name, phx.update := PhxUpdate.Stream) ++ containerMods :+ entries
    )
end extension

extension [A](stream: Signal[streams.LiveStream[A]])
  /** Renders a rendered stream's items; `project` receives each item's DOM `id`.
    *
    * This form follows a signal of the stream. Prefer [[renderIn]], which also sets up the
    * container.
    */
  private[scalive] def stream[Msg](project: (String, Signal[A]) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.SignalStream(stream, project)

  /** Renders a rendered stream's items inside `container`.
    *
    * This form follows a signal of the stream, as in
    * `model.map(_.activity).renderIn(ul)(item => li(...))`. Scalive sets the container's `id` and
    * `phx-update` and each item's DOM `id`.
    */
  private[scalive] def renderIn[Msg](
    container: HtmlTag,
    mods: Mod.Input[Msg]*
  )(
    project: Signal[A] => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    val containerMods =
      Mod.flatten(mods).filterNot(mod => isAttr(mod, "id") || isAttr(mod, "phx-update"))
    val entries = stream.stream { (domId, item) =>
      val element = project(item)
      HtmlElement(
        element.tag,
        element.mods.filterNot(isAttr(_, "id")).prepended(idAttr := domId)
      )
    }
    HtmlElement(
      container,
      Vector(idAttr := stream.map(_.name), phx.update := PhxUpdate.Stream) ++
        containerMods :+ entries
    )
end extension

private def isAttr(mod: Mod[?], expectedName: String): Boolean = mod match
  case Mod.Attr.Static(name, _)                       => name == expectedName
  case Mod.Attr.StaticValueAsPresence(name, _)        => name == expectedName
  case Mod.Attr.SignalValue(name, _)                  => name == expectedName
  case Mod.Attr.SignalOptionalValue(name, _)          => name == expectedName
  case Mod.Attr.SignalValueAsPresence(name, _)        => name == expectedName
  case Mod.Attr.CompositeStatic(name, _)              => name == expectedName
  case Mod.Attr.CompositeSignalValue(name, _)         => name == expectedName
  case Mod.Attr.CompositeSignalOptionalValue(name, _) => name == expectedName
  case Mod.Attr.Binding(name, _)                      => name == expectedName
  case Mod.Attr.SignalBinding(name, _, _)             => name == expectedName
  case Mod.Attr.FormBinding(name, _)                  => name == expectedName
  case Mod.Attr.FormEventBinding(name, _, _)          => name == expectedName
  case Mod.Attr.TypedFormEventBinding(name, _, _)     => name == expectedName
  case Mod.Attr.JsBinding(name, _)                    => name == expectedName
  case Mod.Attr.SignalJsBinding(name, _)              => name == expectedName
  case Mod.Attr.RoutedBinding(name, _)                => name == expectedName
  case Mod.Attr.Group(attrs)                          => attrs.exists(isAttr(_, expectedName))
  case _: Mod.Content[?]                              => false
