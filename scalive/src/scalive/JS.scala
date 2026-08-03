package scalive

import zio.json.*
import zio.json.ast.Json

val JS: JSCommands.JSCommand[Nothing] = JSCommands.empty

object JSCommands:
  opaque type JSCommand[+Msg] = List[Op[Msg]]

  final private case class Op[+Msg](
    renderJson: Option[String] => Json,
    binding: Option[Binding[Msg]])

  final case class Binding[+Msg](msg: Msg)

  def empty: JSCommand[Nothing] = List.empty

  object JSCommand:
    given [Msg]: JsonEncoder[JSCommand[Msg]] =
      JsonEncoder[Json].contramap(ops => Json.Arr(ops.map(_.renderJson(None)).reverse*))

  private def encodeOp[A: JsonEncoder](kind: String, args: A): Json =
    (kind, args).toJsonAST.toOption.getOrElse(Json.Arr(Json.Str(kind), Json.Obj.empty))

  private def classNames(names: String): Seq[String] = names.split("\\s+").toSeq
  private def transitionClasses(names: String | (String, String, String))
    : Option[Seq[Seq[String]]] =
    names match
      case ""                          => None
      case names: String               => Some(Seq(classNames(names), Seq.empty, Seq.empty))
      case t: (String, String, String) => Some(t.toList.map(classNames))

  extension [Msg](ops: JSCommand[Msg])
    private[scalive] def map[Msg2](f: Msg => Msg2): JSCommand[Msg2] =
      ops.map(op => op.copy(binding = op.binding.map(binding => Binding(f(binding.msg)))))

    private def addOp[A: JsonEncoder](kind: String, args: A): JSCommand[Msg] =
      Op(_ => encodeOp(kind, args), None) :: ops

    private def resolved(scope: String): Vector[(Json, Option[(String, Any)])] =
      ops.reverse.zipWithIndex.map { case (op, pushIndex) =>
        val resolvedId =
          if op.binding.isDefined then Some(BindingId.jsPushBindingId(scope, pushIndex)) else None
        val json    = op.renderJson(resolvedId)
        val binding =
          op.binding.map(binding => resolvedId.get -> binding.msg)
        (json, binding)
      }.toVector

    private[scalive] def renderJson(scope: String): String =
      Json.Arr(resolved(scope).map(_._1)*).toJson

    private[scalive] def bindings(scope: String): Map[String, Any] =
      resolved(scope).flatMap(_._2).toMap

    def addClass    = ClassOp("add_class", ops)
    def toggleClass = ClassOp("toggle_class", ops)
    def removeClass = ClassOp("remove_class", ops)

    def dispatch(
      event: String,
      to: DomSelector = DomSelector.current,
      detail: Map[String, String] = Map.empty,
      bubbles: Boolean = true,
      blocking: Boolean = false
    ) =
      ops.addOp(
        "dispatch",
        Args.Dispatch(
          event,
          to.jsonValue,
          Option.when(detail.nonEmpty)(detail),
          Option.when(!bubbles)(bubbles),
          Option.when(blocking)(blocking)
        )
      )

    def exec(attr: String, to: DomSelector = DomSelector.current) =
      ops.addOp(
        "exec",
        Args.Attr(
          attr,
          to.jsonValue
        )
      )

    def focus(to: DomSelector = DomSelector.current) =
      ops.addOp(
        "focus",
        Args.To(to.jsonValue)
      )

    def focusFirst(to: DomSelector = DomSelector.current) =
      ops.addOp(
        "focus_first",
        Args.To(to.jsonValue)
      )

    def hide(
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "hide",
        Args.Hide(
          to.jsonValue,
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

    def ignoreAttributes(
      attrs: Seq[String] = Seq.empty,
      to: DomSelector = DomSelector.current
    ) =
      ops.addOp(
        "ignore_attrs",
        Args.IgnoreAttributes(
          Option.when(attrs.nonEmpty)(attrs),
          to.jsonValue
        )
      )

    def pushNavigate(to: LiveLocation) =
      pushNavigateUnsafe(to.href)

    def pushNavigateUnsafe(href: String) =
      ops.addOp("navigate", Args.Href(href, None))

    def replaceNavigate(to: LiveLocation) =
      replaceNavigateUnsafe(to.href)

    def replaceNavigateUnsafe(href: String) =
      ops.addOp("navigate", Args.Href(href, Some(true)))

    def pushPatch(to: LiveLocation) =
      pushPatchUnsafe(to.href)

    def pushPatchUnsafe(href: String) =
      ops.addOp("patch", Args.Href(href, None))

    def replacePatch(to: LiveLocation) =
      replacePatchUnsafe(to.href)

    def replacePatchUnsafe(href: String) =
      ops.addOp("patch", Args.Href(href, Some(true)))

    def popFocus() =
      ops.addOp("pop_focus", Json.Obj.empty)

    def push[Msg2 >: Msg](
      event: Msg2,
      target: DomSelector = DomSelector.current,
      loading: DomSelector = DomSelector.current,
      pageLoading: Boolean = false
    ): JSCommand[Msg2] =
      val binding = Binding(event)
      Op(
        maybeBindingId =>
          encodeOp(
            "push",
            Args.Push(
              maybeBindingId.getOrElse(BindingId.unresolved()),
              target.jsonValue,
              loading.jsonValue,
              Option.when(!pageLoading)(pageLoading)
            )
          ),
        Some(binding)
      )
        :: ops

    def pushFocus(to: DomSelector = DomSelector.current) =
      ops.addOp("push_focus", Args.To(to.jsonValue))

    def removeAttribute(attr: String, to: DomSelector = DomSelector.current) =
      ops.addOp(
        "remove_attr",
        Args.Attr(
          attr,
          to.jsonValue
        )
      )

    def setAttribute(arg: (String, String), to: DomSelector = DomSelector.current) =
      ops.addOp("set_attr", Args.SetAttribute(attr = arg, to = to.jsonValue))

    def show(
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "show",
        Args.Show(
          to.jsonValue,
          transitionClasses(transition),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    def toggle(
      to: DomSelector = DomSelector.current,
      in: String | (String, String, String) = "",
      out: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true,
      display: String = "block"
    ) =
      ops.addOp(
        "toggle",
        Args.Toggle(
          to.jsonValue,
          ins = transitionClasses(in),
          outs = transitionClasses(out),
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking),
          Option.when(display != "block")(display)
        )
      )

    def toggleAttribute(
      name: String,
      value: String,
      altValue: String = "",
      to: DomSelector = DomSelector.current
    ) =
      ops.addOp(
        "toggle_attr",
        Args.ToggleAttribute(
          attr = Seq(name, value).appendedAll(Option.when(altValue.nonEmpty)(altValue)),
          to.jsonValue
        )
      )

    def transition(
      transition: String | (String, String, String) = "",
      to: DomSelector = DomSelector.current,
      time: Int = 200,
      blocking: Boolean = true
    ) =
      ops.addOp(
        "transition",
        Args.Transition(
          transition match
            case names: String               => Seq(classNames(names), Seq.empty, Seq.empty)
            case t: (String, String, String) => t.toList.map(classNames),
          to.jsonValue,
          Option.when(time != 200)(time),
          Option.when(!blocking)(blocking)
        )
      )

  end extension

  final private[scalive] class ClassOp[Msg](kind: String, ops: JSCommand[Msg]):
    def apply(
      names: String,
      to: DomSelector = DomSelector.current,
      transition: String | (String, String, String) = "",
      time: Int = 200,
      blocking: Boolean = true
    ): JSCommand[Msg] =
      ops.addOp(
        kind,
        Args.ClassChange(
          classNames(names),
          to.jsonValue,
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
    final case class IgnoreAttributes(attrs: Option[Seq[String]], to: Option[String])
        derives JsonEncoder
    final case class Hide(
      to: Option[String],
      transition: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class SetAttribute(
      attr: (String, String),
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
      ins: Option[Seq[Seq[String]]],
      outs: Option[Seq[Seq[String]]],
      time: Option[Int],
      blocking: Option[Boolean],
      display: Option[String])
        derives JsonEncoder
    final case class ToggleAttribute(attr: Seq[String], to: Option[String]) derives JsonEncoder
    final case class Transition(
      transition: Seq[Seq[String]],
      to: Option[String],
      time: Option[Int],
      blocking: Option[Boolean])
        derives JsonEncoder
    final case class Push(
      event: String,
      target: Option[String],
      loading: Option[String],
      pageLoading: Option[Boolean])
        derives JsonEncoder
  end Args

end JSCommands
