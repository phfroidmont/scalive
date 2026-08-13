package scalive

extension [T](items: IterableOnce[T])
  /** Renders `items` as keyed children using an application-defined stable key.
    *
    * Keys identify entries across renders, so reordering can preserve child diffs and event-binding
    * identities instead of treating entries as unrelated positional content. The extractor must be
    * pure: it can be evaluated more than once per item. A key must be unique within this collection
    * and remain equal for the lifetime of the logical item. Its runtime class and string
    * representation must also distinguish it from sibling keys because binding IDs derive from
    * those values. Duplicate, mutable, or string-colliding keys make entry identity ambiguous.
    *
    * @param key
    *   extracts the stable identity of an item
    * @param project
    *   renders an item with its extracted key
    */
  def splitBy[Key, Msg](key: T => Key)(project: (Key, T) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator
      .map(item => Mod.Content.Keyed.Entry(key(item), project(key(item), item))).toVector
    Mod.Content.Keyed(entries)

  /** Renders `items` as keyed children using their current zero-based indexes.
    *
    * Index keys are stable only while items remain in the same positions. Use this for positional
    * content; use [[splitBy]] with a domain key when items can be inserted, removed, or reordered.
    *
    * @param project
    *   renders each item with its current index
    */
  def splitByIndex[Msg](project: (Int, T) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.zipWithIndex.map { case (item, index) =>
      Mod.Content.Keyed.Entry(index, project(index, item))
    }.toVector
    Mod.Content.Keyed(entries)
end extension

extension [T](stream: streams.LiveStream[T])
  /** Renders a LiveView stream as low-level keyed stream content.
    *
    * The supplied DOM ID comes from the stream definition and is the stable identity used by the
    * client protocol. The caller must put this modifier in a container whose `id` is the stream
    * name and whose `phx-update` is [[PhxUpdate.Stream]], and must assign the supplied DOM ID to
    * the root element returned by `project`. Once mounted, the client owns that container's
    * streamed children; mutate them through stream operations rather than ordinary parent diffs.
    *
    * Prefer [[renderIn]] unless manual control of the container or row projection is required.
    *
    * @param project
    *   renders each entry from its protocol-owned DOM ID and value
    */
  def stream[Msg](project: (String, T) => HtmlElement[Msg]): Mod[Msg] =
    val renderedEntries =
      stream.snapshotEntries.iterator
        .map(entry =>
          entry.domId -> Mod.Content.Keyed.Entry(entry.domId, project(entry.domId, entry.value))
        ).toVector

    val renderedByDomId = renderedEntries.toMap

    val snapshotEntries = renderedEntries.map(_._2)

    val entries = stream.entries.iterator
      .map(entry =>
        renderedByDomId.getOrElse(
          entry.domId,
          Mod.Content.Keyed.Entry(entry.domId, project(entry.domId, entry.value))
        )
      ).toVector

    val streamPatch =
      Option.when(stream.inserts.nonEmpty || stream.deleteIds.nonEmpty || stream.reset)(
        Diff.Stream(
          ref = stream.ref,
          inserts = stream.inserts.map(insert =>
            Diff.StreamInsert(
              domId = insert.domId,
              at = insert.at,
              limit = insert.limit,
              updateOnly = insert.updateOnly
            )
          ),
          deleteIds = stream.deleteIds,
          reset = stream.reset
        )
      )

    Mod.Content.Keyed(entries, stream = streamPatch, allEntries = Some(snapshotEntries))
  end stream

  /** Renders this stream in a container while enforcing the stream DOM contract.
    *
    * This helper owns the container's `id` and `phx-update` attributes and every projected row's
    * root `id`. Conflicting attributes supplied through `mods` or `project` are discarded: the
    * container uses the stream name with [[PhxUpdate.Stream]], and each row uses the stable DOM ID
    * produced by the stream definition. A modifier group containing an owned attribute is discarded
    * as a unit, so put unrelated attributes in separate modifiers.
    *
    * After the initial render, the browser owns the container's streamed children. Insert, update,
    * move, reset, and delete rows through the stream operations in the LiveView context; ordinary
    * DOM children in the same container are outside this contract.
    *
    * @param container
    *   the HTML tag used for the stream container
    * @param mods
    *   additional container modifiers, excluding the owned `id` and `phx-update`
    * @param project
    *   renders an entry; its root `id`, if present, is replaced by the stream DOM ID
    */
  def renderIn[Msg](
    container: HtmlTag,
    mods: Mod[Msg]*
  )(
    project: T => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    val containerMods = mods.filterNot(mod => isAttr(mod, "id") || isAttr(mod, "phx-update"))
    val entries       = stream.stream { (domId, item) =>
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

private def isAttr(mod: Mod[?], expectedName: String): Boolean =
  mod match
    case Mod.Attr.Static(name, _)                => name == expectedName
    case Mod.Attr.StaticValueAsPresence(name, _) => name == expectedName
    case Mod.Attr.Binding(name, _)               => name == expectedName
    case Mod.Attr.FormBinding(name, _)           => name == expectedName
    case Mod.Attr.FormEventBinding(name, _, _)   => name == expectedName
    case Mod.Attr.JsBinding(name, _)             => name == expectedName
    case Mod.Attr.RoutedBinding(name, _)         => name == expectedName
    case Mod.Attr.Group(attrs)                   => attrs.exists(isAttr(_, expectedName))
    case _: Mod.Content[?]                       => false
