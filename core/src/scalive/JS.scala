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

  private def classNames(names: String): Seq[String] = names.split("\\s+")
  private def transitionClasses(names: String | (String, String, String))
    : Option[Seq[Seq[String]]] =
    names match
      case ""                          => None
      case names: String               => Some(Seq(classNames(names), Seq.empty, Seq.empty))
      case t: (String, String, String) => Some(t.toList.map(classNames))

  extension (ops: JSCommand)
    private def addOp[A: JsonEncoder](kind: String, args: A): JSCommand =
      (kind, args).toJsonAST.fold(e => throw new IllegalArgumentException(e), identity) :: ops

    def addClass    = ClassOp("add_class", ops)
    def toggleClass = ClassOp("toggle_class", ops)
    def removeClass = ClassOp("remove_class", ops)

    def dispatch(
      event: String,
      to: String = "",
      detail: Map[String, String] = Map.empty,
      bubbles: Boolean = true,
      blocking: Boolean = false
    ) =
      ops.addOp(
        "dispatch",
        Args.Dispatch(
          event,
          Option.when(to.nonEmpty)(to),
          Option.when(detail.nonEmpty)(detail),
          Option.when(!bubbles)(bubbles),
          Option.when(blocking)(blocking)
        )
      )

    def exec(attr: String, to: String = "") =
      ops.addOp(
        "exec",
        Args.Attr(
          attr,
          Option.when(to.nonEmpty)(to)
        )
      )

    def focus(to: String = "") =
      ops.addOp(
        "focus",
        Args.To(Option.when(to.nonEmpty)(to))
      )

    def focusFirst(to: String = "") =
      ops.addOp(
        "focus_first",
        Args.To(Option.when(to.nonEmpty)(to))
      )

    def hide(
      to: String = "",
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "hide",
        Args.Hide(
          Option.when(to.nonEmpty)(to),
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

    def ignoreAttributes(to: String = "") =
      ops.addOp("ignore_attributes", Args.To(Option.when(to.nonEmpty)(to)))

    def navigate(href: String, replace: Boolean = false) =
      ops.addOp("navigate", Args.Href(href, Option.when(replace)(replace)))

    def patch(href: String, replace: Boolean = false) =
      ops.addOp(
        "patch",
        Args.Href(href, Option.when(replace)(replace))
      )

    def popFocus() =
      ops.addOp("pop_focus", Json.Obj.empty)

    def push() = ???

    def pushFocus(to: String = "") =
      ops.addOp("push_focus", Args.To(Option.when(to.nonEmpty)(to)))

    def removeAttribute(attr: String, to: String = "") =
      ops.addOp(
        "remove_attribute",
        Args.Attr(
          attr,
          Option.when(to.nonEmpty)(to)
        )
      )

    def setAttribute(arg: (String, String), to: String = "") =
      ops.addOp("set_attribute", Args.SetAttribute(arg, Option.when(to.nonEmpty)(to)))

    def show(
      to: String = "",
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "show",
        Args.Show(
          Option.when(to.nonEmpty)(to),
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    def toggle(
      to: String = "",
      in: String | (String, String, String) = "",
      out: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "toggle",
        Args.Toggle(
          Option.when(to.nonEmpty)(to),
          transitionClasses(in),
          transitionClasses(out),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    def toggleAttribute(
      name: String,
      value: String,
      altValue: String = "",
      to: String = ""
    ) =
      ops.addOp(
        "toggle_attribute",
        Args.ToggleAttribute(
          Seq(name, value).appendedAll(Option.when(altValue.nonEmpty)(altValue)),
          Option.when(to.nonEmpty)(to)
        )
      )

    def transition(
      transition: String | (String, String, String) = "",
      to: String = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "transition",
        Args.Transition(
          transition match
            case names: String               => Seq(classNames(names), Seq.empty, Seq.empty)
            case t: (String, String, String) => t.toList.map(classNames),
          Option.when(to.nonEmpty)(to),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )
  end extension

  final private[scalive] class ClassOp(kind: String, ops: JSCommand):
    def apply(
      names: String,
      to: String = "",
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        kind,
        Args.ClassChange(
          classNames(names),
          Option.when(to.nonEmpty)(to),
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

  private object Args:
    final case class ClassChange(
      names: Seq[String],
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Dispatch(
      event: String,
      to: Option[String],
      detail: Option[Map[String, String]],
      bubbles: Option[Boolean],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Attr(attr: String, to: Option[String]) derives JsonEncoder
    final case class To(to: Option[String]) derives JsonEncoder
    final case class Hide(
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class SetAttribute(
      arg: (String, String),
      to: Option[String])
        derives JsonEncoder
    final case class Href(href: String, replace: Option[Boolean]) derives JsonEncoder
    final case class Show(
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean],
      display: Option[String])
        derives JsonEncoder
    final case class Toggle(
      to: Option[String],
      in: Option[Seq[Seq[String]]],
      out: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean],
      display: Option[String])
        derives JsonEncoder
    final case class ToggleAttribute(arg: Seq[String], to: Option[String]) derives JsonEncoder
    final case class Transition(
      transition: Seq[Seq[String]],
      to: Option[String],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
  end Args

end JSCommands
