package scalive

import zio.json.*
import zio.json.ast.Json

val JS: JSCommands.JSCommand = JSCommands.empty

object JSCommands:
  opaque type JSCommand = List[Json]

  def empty: JSCommand = List.empty

  object JSCommand:
    given JsonEncoder[JSCommand] =
      JsonEncoder[Json].contramap(ops => Json.Arr(ops.reverse*))

  private def classNames(names: String) = names.split("\\s+")

  extension (ops: JSCommand)
    private def addOp[A: JsonEncoder](kind: String, args: A) =
      (kind, args).toJsonAST.getOrElse(throw new IllegalArgumentException()) :: ops

    def toggleClass(
      names: String,
      to: String = "",
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "toggle_class",
        Args.ToggleClass(
          classNames(names),
          Some(to).filterNot(_.isBlank),
          transition match
            case ""                          => None
            case names: String               => Some(classNames(names))
            case t: (String, String, String) => Some(t.toList),
          Some(time).filterNot(_ == 200),
          Some(blocking).filterNot(_ == true)
        )
      )

  private object Args:
    final case class ToggleClass(
      names: Seq[String],
      to: Option[String],
      transition: Option[Seq[String]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder

end JSCommands
