package scalive

import zio.json.ast.*

enum Diff:
  case Mod(
      static: Seq[String] = Seq.empty,
      dynamic: Seq[Diff.Dynamic] = Seq.empty
  )
  case Split(
      static: Seq[String] = Seq.empty,
      dynamic: Seq[Diff.Dynamic] = Seq.empty
  )
  case Static(value: String)
  case Dynamic(index: Int, diff: Diff)
  case Deleted

object DiffEngine:

  def buildInitJson(lv: RenderedLiveView[?]): Json =
    JsonAstBuilder
      .diffToJson(
        buildDiffValue(lv.static, lv.dynamic, includeUnchanged = true)
      )

  def buildDiffJson(lv: RenderedLiveView[?]): Json =
    Json
      .Obj(
        "diff" -> JsonAstBuilder.diffToJson(
          buildDiffValue(static = Seq.empty, lv.dynamic)
        )
      )

  private def buildDiffValue(
      static: Seq[String],
      dynamic: Seq[RenderedMod[?]],
      includeUnchanged: Boolean = false
  ): Diff.Mod =
    Diff.Mod(
      static = static,
      dynamic = dynamic.zipWithIndex
        .filter(includeUnchanged || _._1.wasUpdated)
        .map {
          case (v: RenderedMod.Dynamic[?, ?], i) =>
            Diff.Dynamic(i, Diff.Static(v.currentValue.toString))
          case (v: RenderedMod.When[?], i) =>
            if v.displayed then
              if includeUnchanged || v.cond.wasUpdated then
                Diff.Dynamic(
                  i,
                  buildDiffValue(
                    v.nested.static,
                    v.nested.dynamic,
                    includeUnchanged = true
                  )
                )
              else
                Diff.Dynamic(
                  i,
                  buildDiffValue(
                    static = Seq.empty,
                    v.nested.dynamic,
                    includeUnchanged
                  )
                )
            else Diff.Dynamic(i, Diff.Deleted)
          case (v: RenderedMod.Split[?, ?], i) =>
            Diff.Dynamic(
              i,
              Diff.Split(
                static = if includeUnchanged then v.static else Seq.empty,
                dynamic = v.dynamic.toList.zipWithIndex
                  .filter(includeUnchanged || _._1.exists(_.wasUpdated))
                  .map[Diff.Dynamic]((mods, i) =>
                    Diff.Dynamic(
                      i,
                      buildDiffValue(
                        static = Seq.empty,
                        dynamic = mods,
                        includeUnchanged
                      )
                    )
                  )
                  .appendedAll(
                    v.removedIndexes
                      .map(i => Diff.Dynamic(i, Diff.Deleted))
                  )
              )
            )
        }
    )
