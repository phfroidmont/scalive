package scalive

import zio.Chunk
import zio.json.JsonEncoder
import zio.json.ast.Json

enum Diff:
  case Tag(
    static: Seq[String] = Seq.empty,
    dynamic: Seq[Diff.Dynamic] = Seq.empty)
  case Comprehension(
    static: Seq[String] = Seq.empty,
    entries: Seq[Diff.Dynamic] = Seq.empty,
    count: Int = 0)
  case Value(value: String)
  case Dynamic(key: String, diff: Diff)
  case Deleted

object Diff:
  given JsonEncoder[Diff] = JsonEncoder[Json].contramap(toJson(_))

  private def toJson(diff: Diff): Json =
    diff match
      case Diff.Tag(static, dynamic) =>
        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appendedAll(
              dynamic.map(d => d.key -> toJson(d.diff))
            )
        )
      case Diff.Comprehension(static, entries, count) =>
        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appended(
              "k" ->
                Json
                  .Obj(
                    entries.map(d => d.key -> toJson(d.diff))*
                  ).add("kc", Json.Num(count))
            )
        )
      case Diff.Value(value)         => Json.Str(value)
      case Diff.Dynamic(index, diff) =>
        Json.Obj(index.toString -> toJson(diff))
      case Diff.Deleted => Json.Bool(false)
end Diff
