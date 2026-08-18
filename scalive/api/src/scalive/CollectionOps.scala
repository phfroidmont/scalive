package scalive

extension [A](items: IterableOnce[A])
  /** Declares keyed child content using exact domain-key equality. */
  def splitBy[Key, Msg](key: A => Key)(project: (Key, A) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.map { item =>
      val itemKey = key(item)
      Mod.Content.Keyed.Entry(itemKey, project(itemKey, item))
    }.toVector
    Mod.Content.Keyed(entries)

  /** Declares positionally keyed child content. */
  def splitByIndex[Msg](project: (Int, A) => HtmlElement[Msg]): Mod[Msg] =
    val entries = items.iterator.zipWithIndex.map { case (item, index) =>
      Mod.Content.Keyed.Entry(index, project(index, item))
    }.toVector
    Mod.Content.Keyed(entries)

extension [A, Items <: Iterable[A]](items: Signal[Items])
  /** Declares retained keyed rows projected from a collection signal. */
  def splitBy[Key, Msg](
    key: A => Key
  )(
    project: (Key, Signal[A]) => HtmlElement[Msg]
  ): Mod[Msg] =
    Mod.Content.SignalKeyed(items.map(values => values: Iterable[A]), key, project)

  /** Declares retained rows whose identity is their current index. */
  def splitByIndex[Msg](project: (Int, Signal[A]) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.SignalKeyedByIndex(items.map(values => values: Iterable[A]), project)

extension [A](stream: streams.LiveStream[A])
  /** Declares streamed rows without exposing stream runtime state. */
  def stream[Msg](project: (String, A) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.Stream(stream, project)

  /** Declares a stream container whose IDs and patch mode are finalized by the renderer. */
  def renderIn[Msg](
    container: HtmlTag,
    mods: Mod[Msg]*
  )(
    project: A => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    container(mods, Mod.Content.Stream(stream, (_, item) => project(item)))

extension [A](stream: Signal[streams.LiveStream[A]])
  /** Declares signal-backed streamed rows. */
  def stream[Msg](project: (String, Signal[A]) => HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.SignalStream(stream, project)

  /** Declares a signal-backed stream container finalized by the renderer. */
  def renderIn[Msg](
    container: HtmlTag,
    mods: Mod[Msg]*
  )(
    project: Signal[A] => HtmlElement[Msg]
  ): HtmlElement[Msg] =
    container(mods, Mod.Content.SignalStream(stream, (_, item) => project(item)))
