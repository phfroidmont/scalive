package scalive

import zio.http.{Request, URL}
import zio.test.*

object LiveLayoutsSpec extends ZIOSpecDefault:
  override def spec = suite("LiveLayoutsSpec")(
    test("ordinary context adaptation changes only the context value") {
      final case class OuterContext(layoutContext: String)
      enum Message:
        case Clicked

      var received: Option[LiveLayoutContext[Int, String]] = None
      val layout                                           = LiveLayout[Int, String]([Msg] =>
        (content, context) =>
          received = Some(context)
          content
      )
      val adapted                        = layout.forContext[OuterContext](_.layoutContext)
      val params                         = Signal.source[Int](new Object)
      val request                        = Signal.source[Request](new Object)
      val currentUrl                     = Signal.source[URL](new Object)
      val content                        = div[Message]()
      val outerContext                   = OuterContext("selected")
      val rendered: HtmlElement[Message] =
        adapted.view(content, LiveLayoutContext(params, request, currentUrl, outerContext))
      val passedContext = received.get

      assertTrue(
        rendered eq content,
        passedContext.params eq params,
        passedContext.request eq request,
        passedContext.currentUrl eq currentUrl,
        passedContext.context == "selected"
      )
    },
    test("root context adaptation projects key and render independently and forwards inputs") {
      final case class OuterContext(layoutContext: String)
      enum Message:
        case Submitted

      var projectedInputs                                        = Vector.empty[OuterContext]
      var keyContext: Option[LiveRootLayoutContext[Int, String]] = None
      var renderInput: Option[(Option[String], LiveRootLayoutContext[Int, String])] = None

      val root = LiveRootLayout.dynamic[Int, String](context =>
        keyContext = Some(context)
        s"root:${context.context}"
      )([Msg] =>
        (content, title, context) =>
          renderInput = Some((title, context))
          content
      )
      val adapted = root.forContext[OuterContext] { context =>
        projectedInputs :+= context
        context.layoutContext
      }
      val request      = Request.get(URL.root)
      val currentUrl   = URL.decode("/current").toOption.get
      val outerContext = OuterContext("selected")
      val context      = LiveRootLayoutContext(42, request, currentUrl, outerContext)
      val content      = div[Message]()
      val title        = Some("Page title")

      val key                            = adapted.key(context)
      val rendered: HtmlElement[Message] = adapted.render(content, title, context)
      val renderedInput                  = renderInput.get
      val keyedInput                     = keyContext.get

      assertTrue(
        key == "root:selected",
        rendered eq content,
        projectedInputs == Vector(outerContext, outerContext),
        keyedInput.params == 42,
        keyedInput.request eq request,
        keyedInput.currentUrl eq currentUrl,
        keyedInput.context == "selected",
        renderedInput._1 == title,
        renderedInput._2.params == 42,
        renderedInput._2.request eq request,
        renderedInput._2.currentUrl eq currentUrl,
        renderedInput._2.context == "selected"
      )
    },
    test("context adapters support identity and composition") {
      var received = ""
      val layout   = LiveLayout[Any, String]([Msg] =>
        (content, context) =>
          received = context.context
          content
      )
      val composed = layout
        .forContext[Int](_.toString)
        .forContext[Boolean](value => if value then 1 else 0)
      val params     = Signal.source[Any](new Object)
      val request    = Signal.source[Request](new Object)
      val currentUrl = Signal.source[URL](new Object)
      val content    = div()

      val identityResult = LiveLayout.identity
        .forContext[String](identity)
        .view(content, LiveLayoutContext(params, request, currentUrl, "unchanged"))
      val composedResult = composed.view(
        content,
        LiveLayoutContext(params, request, currentUrl, true)
      )

      assertTrue(identityResult eq content, composedResult eq content, received == "1")
    }
  )
end LiveLayoutsSpec
