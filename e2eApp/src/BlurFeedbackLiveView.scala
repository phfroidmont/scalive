import zio.ZIO

import scalive.*
import scalive.codecs.StringAsIsEncoder

/** Fixture for experimenting with dedicated blur-triggered form feedback.
  *
  * The marker and normalization behavior here are fixture-only and are not a supported Scalive API.
  */
class BlurFeedbackLiveView extends LiveView[BlurFeedbackLiveView.Msg, BlurFeedbackLiveView.Model]:
  import BlurFeedbackLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Updated(event)   => ZIO.succeed(update(model, event, EventKind.Change))
    case Msg.Submitted(event) => ZIO.succeed(update(model, event, EventKind.Submit))
    case Msg.ChoiceOutput(ChoiceOutput.Selected(value)) =>
      val status = if model.values(ChoiceField) == value then model.status else "Edited"
      ZIO.succeed(
        model.copy(values = model.values.updated(ChoiceField, value), status = status)
      )
    case Msg.ChoiceOutput(ChoiceOutput.Left) =>
      ZIO.succeed(
        model.copy(
          blurred = model.blurred + ChoiceField,
          blurCount = model.blurCount + 1,
          events = model.events :+ EventRecord(
            model.nextEventId,
            "change",
            Some(ChoiceField),
            model.values,
            model.values("phone")
          ),
          nextEventId = model.nextEventId + 1
        )
      )
    case Msg.Reset => ZIO.succeed(Model())

  override def view(model: Signal[Model]) =
    div(
      form(
        idAttr := FormId,
        on.change.form(FormCodec.formData)(Msg.Updated(_)),
        on.submit.form(FormCodec.formData)(Msg.Submitted(_)),
        // The marker is temporary: this input must dispatch synchronously, without rate limiting.
        input(typ := "hidden", idAttr := "blur-trigger"),
        renderField("plain", model),
        renderField("timed", model, phx.debounce     := 500),
        renderField("throttled", model, phx.throttle := 500),
        renderField("blur-only", model, phx.debounce := "blur"),
        renderField("phone", model),
        renderField("other", model),
        button(typ := "submit", "Submit")
      ),
      liveComponent(
        ChoiceComponent,
        id = "choice",
        props = model.map { current =>
          ChoiceProps(
            selected = current.values(ChoiceField),
            invalid = current.values(ChoiceField).isEmpty &&
              (current.all || current.blurred.contains(ChoiceField))
          )
        },
        onOutput = Msg.ChoiceOutput.apply
      ),
      button(idAttr := "outside", typ := "button", "Outside"),
      button(idAttr := "reset", typ   := "button", on.click(Msg.Reset), "Reset"),
      div(idAttr    := "blur-count", model.map(_.blurCount.toString)),
      div(idAttr    := "change-count", model.map(_.changeCount.toString)),
      div(idAttr    := "submit-count", model.map(_.submitCount.toString)),
      div(idAttr    := "status", model.map(_.status)),
      ul(
        idAttr := "events",
        model.map(_.events).splitBy(_.id) { (_, event) =>
          li(
            dataAttr("event-kind")  := event.map(_.kind),
            dataAttr("blur-target") := event.map(_.blurTarget.getOrElse("")),
            dataAttr("phone")       := event.map(_.values("phone")),
            dataAttr("phone-after") := event.map(_.phoneAfter),
            dataAttr("other")       := event.map(_.values("other")),
            event.map(_.text)
          )
        }
      )
    )

  private def renderField(name: String, model: Signal[Model], rateLimit: Mod[Nothing]*) =
    val fieldValue = model.map(_.values(name))
    val invalid    = model.map(current =>
      current.values(name).isEmpty && (current.all || current.blurred.contains(name))
    )

    div(
      input(
        typ      := "text",
        idAttr   := name,
        nameAttr := name,
        value    := fieldValue,
        aria.invalid.optional(invalid.map(value => Option.when(value)("true"))),
        on.blur(blurFeedback(name)),
        rateLimit
      ),
      span(idAttr := s"$name-error", invalid.map(value => if value then "Required" else "")),
      span(idAttr := s"server-$name", fieldValue)
    )

  private def blurFeedback(name: String) =
    JS.setAttribute("phx-value-scalive-blur" -> name, to = FormSelector)
      .dispatch("input", to = BlurTriggerSelector)
      .removeAttribute("phx-value-scalive-blur", to = FormSelector)

  private def update(model: Model, event: RawFormEvent[FormData], kind: EventKind): Model =
    val rawValues  = Fields.iterator.map(name => name -> event.raw.getOrElse(name, "")).toMap
    val blurTarget = event.metadata
      .get("scalive-blur")
      .filter(KnownFields.contains)
    val values = blurTarget match
      case Some("phone") => rawValues.updated("phone", rawValues("phone").trim)
      case _             => rawValues
    val status  = if values == model.values then model.status else "Edited"
    val history = EventRecord(
      id = model.nextEventId,
      kind = kind.label,
      blurTarget = blurTarget,
      values = rawValues,
      phoneAfter = values("phone")
    )

    kind match
      case EventKind.Change =>
        model.copy(
          values = values,
          blurred = blurTarget.fold(model.blurred)(model.blurred + _),
          blurCount = model.blurCount + blurTarget.size,
          changeCount = model.changeCount + (if blurTarget.isEmpty then 1 else 0),
          status = status,
          events = model.events :+ history,
          nextEventId = model.nextEventId + 1
        )
      case EventKind.Submit =>
        model.copy(
          values = values,
          all = true,
          submitCount = model.submitCount + 1,
          status = status,
          events = model.events :+ history,
          nextEventId = model.nextEventId + 1
        )
  end update
end BlurFeedbackLiveView

object BlurFeedbackLiveView:
  private val ChoiceField = "choice"
  private val Fields      =
    Vector("plain", "timed", "throttled", "blur-only", "phone", "other", ChoiceField)
  private val KnownFields         = Fields.toSet
  private val FormId              = "blur-feedback-form"
  private val FormSelector        = DomSelector.css(s"#$FormId")
  private val BlurTriggerSelector = DomSelector.css("#blur-trigger")
  private val formAttr            = htmlAttr("form", StringAsIsEncoder)

  enum Msg:
    case Updated(event: RawFormEvent[FormData])
    case Submitted(event: RawFormEvent[FormData])
    case ChoiceOutput(output: BlurFeedbackLiveView.ChoiceOutput)
    case Reset

  enum ChoiceOutput:
    case Selected(value: String)
    case Left

  final case class ChoiceProps(selected: String, invalid: Boolean)

  object ChoiceComponent
      extends LiveComponent.WithOutput[
        ChoiceProps,
        ChoiceComponent.Msg,
        ChoiceComponent.Model,
        ChoiceOutput
      ]:
    enum Msg:
      case Searched(event: RawFormEvent[FormData])
      case Select
      case Left

    final case class Model(search: String = "")

    def mount(props: ChoiceProps, ctx: MountContext) = ZIO.succeed(Model())

    def handleMessage(props: ChoiceProps, model: Model, ctx: MessageContext) =
      case Msg.Searched(event) =>
        ZIO.succeed(model.copy(search = event.raw.getOrElse("query", "")))
      case Msg.Select => ctx.emit(ChoiceOutput.Selected("selected")).as(model)
      case Msg.Left   => ctx.emit(ChoiceOutput.Left).as(model)

    override def view(
      props: Signal[ChoiceProps],
      model: Signal[Model],
      self: ComponentRef[Msg]
    ) =
      div(
        dom.hook("BlurFeedback", DomRef("choice-widget")),
        form(
          idAttr := "choice-search-form",
          on.change.form(FormCodec.formData)(Msg.Searched(_)),
          phx.target(self),
          input(
            typ      := "search",
            idAttr   := "choice-search",
            nameAttr := "query",
            value    := model.map(_.search),
            phx.target(self)
          )
        ),
        span(idAttr := "server-query", model.map(_.search)),
        button(
          idAttr := "choice-option",
          typ    := "button",
          on.click(Msg.Select),
          phx.target(self),
          "selected"
        ),
        input(
          typ      := "hidden",
          nameAttr := ChoiceField,
          formAttr := FormId,
          value    := props.map(_.selected)
        ),
        span(idAttr := "server-choice", props.map(_.selected)),
        span(
          idAttr := "choice-error",
          props.map(current => if current.invalid then "Required" else "")
        ),
        button(
          idAttr := "choice-close",
          typ    := "button",
          hidden := true,
          on.click(JS.push(Msg.Left)),
          phx.target(self)
        )
      )
  end ChoiceComponent

  private enum EventKind(val label: String):
    case Change extends EventKind("change")
    case Submit extends EventKind("submit")

  final case class EventRecord(
    id: Int,
    kind: String,
    blurTarget: Option[String],
    values: Map[String, String],
    phoneAfter: String):
    def text: String =
      val target   = blurTarget.getOrElse("")
      val snapshot = Fields.map(name => s"$name=${values(name)}").mkString(",")
      s"$kind|$target|$snapshot"

  final case class Model(
    values: Map[String, String] = Fields.map(_ -> "").toMap,
    blurred: Set[String] = Set.empty,
    all: Boolean = false,
    blurCount: Int = 0,
    changeCount: Int = 0,
    submitCount: Int = 0,
    status: String = "Preserved",
    events: Vector[EventRecord] = Vector.empty,
    nextEventId: Int = 0)
end BlurFeedbackLiveView
