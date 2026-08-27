package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*
import scalive.codecs.BooleanAsTrueFalseStringEncoder
import scalive.docs.DocumentationApplication

// docs:start navigation-example
final class NavigationExample extends LiveView[NavigationExample.Msg, NavigationExample.Model]:
  import NavigationExample.*

  private val ariaPressed = htmlAttr("aria-pressed", BooleanAsTrueFalseStringEncoder)

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Select(query) => ZIO.succeed(Model(query))
    case Msg.Reset         => ZIO.succeed(Model.initial)

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val destination = model.map(model => searchLocation(model.query))
    div(
      cls := "docs-navigation-example",
      sectionTag(
        cls                          := "docs-navigation-step docs-navigation-parameters",
        dataAttr("example-controls") := "",
        headerTag(
          span(cls := "docs-navigation-step-number", aria.hidden := true, "01"),
          div(
            h3("Choose search parameters"),
            p("Select a typed value for the route's optional q parameter.")
          )
        ),
        div(
          cls        := "docs-navigation-presets",
          aria.label := "Search query presets",
          Presets.map(query =>
            button(
              typ                           := "button",
              cls                           := "docs-navigation-preset",
              dataAttr("navigation-preset") := query.value,
              ariaPressed                   := model.map(_.query == query),
              on.click(Msg.Select(query)),
              query.label
            )
          )
        )
      ),
      sectionTag(
        cls := "docs-navigation-step docs-navigation-result",
        headerTag(
          span(cls := "docs-navigation-step-number", aria.hidden := true, "02"),
          div(
            h3("Navigate with a LiveLocation"),
            p("One checked destination, two browser-history choices.")
          )
        ),
        div(
          cls := "docs-navigation-route",
          div(
            span(cls                          := "docs-navigation-route-label", "Typed query"),
            code(dataAttr("navigation-query") := "", model.map(_.query.value))
          ),
          div(
            span(cls := "docs-navigation-route-label", "Encoded destination"),
            code(dataAttr("navigation-destination") := "", destination.map(_.href))
          )
        ),
        div(
          cls := "docs-navigation-actions",
          link.pushNavigate(
            destination,
            cls                         := "docs-navigation-primary",
            dataAttr("push-navigation") := "",
            span("Open search"),
            small("Push history")
          ),
          link.replaceNavigate(
            destination,
            cls                            := "docs-navigation-secondary",
            dataAttr("replace-navigation") := "",
            span("Open and replace"),
            small("Replace history")
          ),
          button(
            cls := "docs-navigation-reset",
            typ := "button",
            on.click(Msg.Reset),
            "Reset"
          )
        )
      )
    )
  end view
end NavigationExample

object NavigationExample:
  enum SearchPreset(val label: String, val value: String):
    case LiveView   extends SearchPreset("LiveView", "LiveView")
    case Streams    extends SearchPreset("Streams", "streams")
    case TypedForms extends SearchPreset("Typed forms", "typed forms")

  val Presets = SearchPreset.values.toVector

  final case class Model(query: SearchPreset)

  object Model:
    val initial = Model(SearchPreset.LiveView)

  enum Msg:
    case Select(query: SearchPreset)
    case Reset

  private def searchLocation(query: SearchPreset): LiveLocation =
    DocumentationApplication.SearchRouteBuilder.location(Some(query.value))
// docs:end navigation-example
