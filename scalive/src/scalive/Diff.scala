package scalive

import zio.Chunk
import zio.json.JsonEncoder
import zio.json.ast.Json

enum Diff:
  case Tag(
    static: Seq[String] = Seq.empty,
    dynamic: Seq[Diff.Dynamic] = Seq.empty,
    events: Seq[Diff.Event] = Seq.empty)
  case Comprehension(
    static: Seq[String] = Seq.empty,
    entries: Seq[Diff.Dynamic | Diff.IndexChange] = Seq.empty,
    count: Int = 0,
    stream: Option[Diff.Stream] = None)
  case Value(value: String)
  case Dynamic(index: Int, diff: Diff)
  case Deleted

extension (diff: Diff)
  def isEmpty: Boolean = diff match
    case Diff.Tag(static, dynamic, events) =>
      static.isEmpty && dynamic.isEmpty && events.isEmpty
    case _: Diff.Comprehension => false
    case _: Diff.Value         => false
    case _: Diff.Dynamic       => false
    case Diff.Deleted          => false

object Diff:
  given JsonEncoder[Diff] = JsonEncoder[Json].contramap(toJson(_))

  final case class IndexChange(index: Int, previousIndex: Int)

  final case class Event(name: String, payload: Json)

  final case class StreamInsert(
    domId: String,
    at: Int,
    limit: Option[Int],
    updateOnly: Option[Boolean])

  final case class Stream(
    ref: String,
    inserts: Seq[StreamInsert],
    deleteIds: Seq[String],
    reset: Boolean)

  private def toJson(diff: Diff): Json =
    diff match
      case Diff.Tag(static, dynamic, events) =>
        val eventsJson =
          Option.when(events.nonEmpty)(
            "e" -> Json.Arr(events.map(event => Json.Arr(Json.Str(event.name), event.payload))*)
          )

        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appendedAll(
              dynamic.map(d => d.index.toString -> toJson(d.diff))
            )
            .appendedAll(eventsJson)
        )
      case Diff.Comprehension(static, entries, count, stream) =>
        val keyedEntries =
          Json
            .Obj(
              entries.map {
                case Diff.Dynamic(index, diff) =>
                  index.toString -> toJson(diff)
                case Diff.IndexChange(index, previousIndex) =>
                  index.toString -> Json.Num(previousIndex)
              }*
            ).add("kc", Json.Num(count))

        val streamJson =
          stream.map { streamPatch =>
            val inserts = streamPatch.inserts.map { insert =>
              Json.Arr(
                Json.Str(insert.domId),
                Json.Num(insert.at),
                insert.limit.map(value => Json.Num(value)).getOrElse(Json.Null),
                insert.updateOnly.map(value => Json.Bool(value)).getOrElse(Json.Null)
              )
            }

            val base = Chunk(
              Json.Str(streamPatch.ref),
              Json.Arr(inserts*),
              Json.Arr(streamPatch.deleteIds.map(Json.Str(_))*)
            )

            val withReset =
              if streamPatch.reset then base.appended(Json.Bool(true))
              else base

            Json.Arr(withReset*)
          }

        Json.Obj(
          Option
            .when(static.nonEmpty)("s" -> Json.Arr(static.map(Json.Str(_))*))
            .to(Chunk)
            .appended("k" -> keyedEntries)
            .appendedAll(streamJson.map(json => "stream" -> json))
        )
      case Diff.Value(value)         => Json.Str(value)
      case Diff.Dynamic(index, diff) =>
        Json.Obj(index.toString -> toJson(diff))
      case Diff.Deleted => Json.Str("")
end Diff
