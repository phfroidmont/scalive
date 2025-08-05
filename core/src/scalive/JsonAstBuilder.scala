package scalive

import zio.json.ast.*

object JsonAstBuilder:
  def buildInit(static: Seq[String], dynamic: Seq[RenderedMod[?]]): Json =
    Json
      .Obj("s" -> Json.Arr(static.map(Json.Str(_))*))
      .merge(buildDiffValue(dynamic, includeUnchanged = true))

  def buildDiff(dynamic: Seq[RenderedMod[?]]): Json =
    Json.Obj("diff" -> buildDiffValue(dynamic))

  private def buildDiffValue(
      dynamic: Seq[RenderedMod[?]],
      includeUnchanged: Boolean = false
  ): Json =
    Json.Obj(
      dynamic.zipWithIndex.filter(includeUnchanged || _._1.wasUpdated).map {
        case (v: RenderedMod.Dynamic[?, ?], i) =>
          i.toString -> Json.Str(v.currentValue.toString)
        case (v: RenderedMod.When[?], i) =>
          if v.displayed then
            if includeUnchanged || v.dynCond.wasUpdated then
              i.toString -> v.nested.buildInitJson
            else i.toString -> buildDiffValue(v.nested.dynamic)
          else i.toString -> Json.Bool(false)
      }*
    )
