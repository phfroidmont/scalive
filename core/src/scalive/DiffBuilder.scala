package scalive

object DiffBuilder:
  def build(
      static: Seq[String],
      dynamic: Seq[LiveDyn[?]],
      includeUnchanged: Boolean = false
  ): Diff.Tag =
    Diff.Tag(
      static = static,
      dynamic = dynamic.zipWithIndex
        .filter(includeUnchanged || _._1.wasUpdated)
        .map {
          case (v: LiveDyn.Value[?, ?], i) =>
            Diff.Dynamic(i, Diff.Static(v.currentValue.toString))
          case (v: LiveDyn.When[?], i) => build(v, i, includeUnchanged)
          case (v: LiveDyn.Split[?, ?], i) =>
            Diff.Dynamic(i, build(v, includeUnchanged))
        }
    )

  private def build(
      mod: LiveDyn.When[?],
      index: Int,
      includeUnchanged: Boolean
  ): Diff.Dynamic =
    if mod.displayed then
      if includeUnchanged || mod.cond.wasUpdated then
        Diff.Dynamic(
          index,
          build(
            mod.nested.static,
            mod.nested.dynamic,
            includeUnchanged = true
          )
        )
      else
        Diff.Dynamic(
          index,
          build(
            static = Seq.empty,
            mod.nested.dynamic,
            includeUnchanged
          )
        )
    else Diff.Dynamic(index, Diff.Deleted)

  private def build(
      mod: LiveDyn.Split[?, ?],
      includeUnchanged: Boolean
  ): Diff.Split =
    Diff.Split(
      static = if includeUnchanged then mod.static else Seq.empty,
      entries = mod.dynamic.toList.zipWithIndex
        .filter(includeUnchanged || _._1.exists(_.wasUpdated))
        .map[Diff.Dynamic]((mods, i) =>
          Diff.Dynamic(
            i,
            build(
              static = Seq.empty,
              dynamic = mods,
              includeUnchanged
            )
          )
        )
        .appendedAll(
          mod.removedIndexes
            .map(i => Diff.Dynamic(i, Diff.Deleted))
        )
    )
