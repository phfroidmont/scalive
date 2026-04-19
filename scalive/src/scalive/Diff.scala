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
    entries: Seq[Diff.Dynamic | Diff.IndexChange] = Seq.empty,
    count: Int = 0)
  case Value(value: String)
  case Dynamic(index: Int, diff: Diff)
  case Deleted

extension (diff: Diff)
  def isEmpty: Boolean = diff match
    case Diff.Tag(static, dynamic) => static.isEmpty && dynamic.isEmpty
    case _: Diff.Comprehension     => false
    case _: Diff.Value             => false
    case _: Diff.Dynamic           => false
    case Diff.Deleted              => false

object Diff:
  given JsonEncoder[Diff] = JsonEncoder[Json].contramap(toJson(_))

  final case class IndexChange(index: Int, previousIndex: Int)

  private def toJson(diff: Diff): Json =
    diff match
      case Diff.Tag(static, dynamic) =>
        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appendedAll(
              dynamic.map(d => d.index.toString -> toJson(d.diff))
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
                    entries.map {
                      case Diff.Dynamic(index, diff) =>
                        index.toString -> toJson(diff)
                      case Diff.IndexChange(index, previousIndex) =>
                        index.toString -> Json.Num(previousIndex)
                    }*
                  ).add("kc", Json.Num(count))
            )
        )
      case Diff.Value(value)         => Json.Str(value)
      case Diff.Dynamic(index, diff) =>
        Json.Obj(index.toString -> toJson(diff))
      case Diff.Deleted => Json.Str("")
end Diff
