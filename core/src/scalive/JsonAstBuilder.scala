package scalive

import zio.Chunk
import zio.json.ast.*

object JsonAstBuilder:

  def diffToJson(diff: Diff): Json =
    diff match
      case Diff.Mod(static, dynamic) =>
        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appendedAll(
              dynamic.map(d => d.index.toString -> diffToJson(d.diff))
            )
        )
      case Diff.Split(static, dynamic) =>
        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appendedAll(
              Option.when(dynamic.nonEmpty)(
                "d" ->
                  Json.Obj(
                    dynamic.map(d => d.index.toString -> diffToJson(d.diff))*
                  )
              )
            )
        )
      case Diff.Static(value) => Json.Str(value)
      case Diff.Dynamic(index, diff) =>
        Json.Obj(index.toString -> diffToJson(diff))
      case Diff.Deleted => Json.Bool(false)
