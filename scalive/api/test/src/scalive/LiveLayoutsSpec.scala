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
      val layout = new LiveLayout[Int, String]:
        def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Int, String]) =
          received = Some(context)
          content
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

      val root = new LiveRootLayout[Int, String]:
        override def key(context: LiveRootLayoutContext[Int, String]) =
          keyContext = Some(context)
          s"root:${context.context}"

        def view[Msg](
          content: HtmlElement[Msg],
          title: Option[String],
          context: LiveRootLayoutContext[Int, String]
        ) =
          renderInput = Some((title, context))
          content
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
      val rendered: HtmlElement[Message] = adapted.view(content, title, context)
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
    test("root layouts are identified by their class unless they override the key") {
      final class FirstRoot extends LiveRootLayout[Any, String]:
        def view[Msg](
          content: HtmlElement[Msg],
          title: Option[String],
          context: LiveRootLayoutContext[Any, String]
        ) = content
      final class SecondRoot extends LiveRootLayout[Any, String]:
        def view[Msg](
          content: HtmlElement[Msg],
          title: Option[String],
          context: LiveRootLayoutContext[Any, String]
        ) = content
      final case class Selected(language: String)

      val context = LiveRootLayoutContext((), Request.get(URL.root), URL.root, "en")

      assertTrue(
        FirstRoot().key(context) == FirstRoot().key(context),
        FirstRoot().key(context) != SecondRoot().key(context),
        FirstRoot().forContext[Selected](_.language).key(context.copy(context = Selected("en"))) ==
          FirstRoot().key(context),
        LiveRootLayout.identity.key(context) == "scalive:identity-root"
      )
    },
    test("context adapters support identity and composition") {
      var received = ""
      val layout   = new LiveLayout[Any, String]:
        def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, String]) =
          received = context.context
          content
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
