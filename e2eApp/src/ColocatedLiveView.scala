import ColocatedLiveView.*
import zio.ZIO

import scalive.*

class ColocatedLiveView extends LiveView[Msg, Model]:

  def mount(ctx: MountContext) =
    ZIO.succeed(Model())

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.SubmitPhone(phone) => ZIO.succeed(model.copy(phone = phone))
    case Msg.PushJs             =>
      ctx.client.exec(JS.toggle(to = DomSelector.css("#hello"))).as(model)

  override def view(model: Signal[Model]) =
    div(
      form(
        on.submit(params => Msg.SubmitPhone(params.getOrElse("user[phone_number]", ""))),
        input(
          typ := "text",
          dom.hook(".PhoneNumber", DomRef("user-phone-number")),
          nameAttr    := "user[phone_number]",
          placeholder := "phone"
        )
      ),
      p(idAttr := "phone", model.map(_.phone)),
      div(
        dom.hook(".Runtime", DomRef("runtime")),
        styleAttr := "display: none;",
        "Runtime hook works!"
      ),
      button(on.click(Msg.PushJs), "Push JS from server"),
      h1(idAttr := "hello", "Hello!"),
      syntaxHighlight("""
                        |defmodule SyntaxHighlight do
                        |  @behaviour Phoenix.Component.MacroComponent
                        |end
                        |""".stripMargin),
      syntaxHighlight("""
                        |defmodule MyAppWeb.ThermostatLive do
                        |  use MyAppWeb, :live_view
                        |
                        |  def render(assigns) do
                        |    ~H\"\"\"
                        |    Current temperature: @temperature
                        |    <button phx-click="inc_temperature">+</button>
                        |    \"\"\"
                        |  end
                        |end
                        |""".stripMargin),
      colocatedCssFixtures
    )
end ColocatedLiveView

object ColocatedLiveView:
  enum Msg:
    case SubmitPhone(phone: String)
    case PushJs

  final case class Model(phone: String = "")

  private def colocatedCssFixtures =
    div(
      p(
        dataAttr("test") := "global",
        cls              := "test-global-css",
        "Should have red background"
      ),
      scopedCssFixture,
      p(
        dataAttr("test") := "scoped",
        cls              := "test-scoped-css",
        "Should have no background (out of scope)"
      ),
      lowerBoundFixture(inclusive = true),
      lowerBoundFixture(inclusive = false)
    )

  private def scopedCssFixture =
    div(
      dataAttr("colocated-scope") := "blue",
      scopedColor("blue", "Should have blue background"),
      scopedBoundary,
      scopedBoundary,
      div(
        dataAttr("colocated-scope") := "yellow",
        scopedColor("yellow", "Should have yellow background"),
        scopedBoundary
      ),
      scopedBoundary,
      div(
        dataAttr("colocated-scope") := "green",
        scopedColor("green", "Should have green background"),
        scopedBoundary
      )
    )

  private def scopedBoundary =
    span(
      dataAttr("colocated-scope-boundary") := "",
      dataAttr("test-scoped")              := "none",
      cls                                  := "test-scoped-css",
      "Should have no background (scope root)",
      scopedColor("blue", "Should have blue background")
    )

  private def scopedColor(color: String, text: String) =
    span(
      dataAttr("colocated-member") := color,
      dataAttr("test-scoped")      := color,
      cls                          := "test-scoped-css",
      text
    )

  private def lowerBoundFixture(inclusive: Boolean) =
    val bound = if inclusive then "inclusive" else "exclusive"
    val flex  = if inclusive then "yes" else "no"
    val text  = if inclusive then "Should" else "Shouldn't"

    div(
      dataAttr("test-lower-bound-container") := "",
      dataAttr("colocated-lower-bound")      := bound,
      cls                                    := "container",
      (1 to 3).map(index => p(dataAttr("test-inclusive") := flex, s"$text Flex $index"))
    )

  private def syntaxHighlight(code: String) =
    val highlighted = code.trim
      .replace("<button", "&lt;<span class=\"nt\">button</span>")
      .replace("</button>", "&lt;/<span class=\"nt\">button</span>&gt;")
      .replace("@temperature", "<span class=\"na\">@temperature</span>")

    rawHtml(
      s"""<pre class="highlight"><style>.highlight { padding: 8px; border-radius: 4px; }</style>$highlighted</pre>"""
    )
end ColocatedLiveView
