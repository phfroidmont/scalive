package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object JsPayloadBindingSpec extends ZIOSpecDefault:
  override def spec = suite("JsPayloadBindingSpec")(
    test("renders compound commands and resolves push messages from the live payload value") {
      val command = JS.dispatch("input").pushWithValue(value => s"blurred:$value")
      val compiled = RenderProgram.compile[Unit, String](_ => input(on.blur(command)))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        message = candidate.bindings
          .resolve(id).get
           .dispatch(BindingPayload.Params(Map("value" -> "  exact whitespace  ")))
        missing = candidate.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty))
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        id.encoded == "j1:js:1",
        message == Right(BindingDispatch.Owner("blurred:  exact whitespace  ")),
        missing == Right(BindingDispatch.Owner("blurred:")),
        html.contains("&quot;dispatch&quot;"),
        html.contains("&quot;push&quot;"),
        html.contains("j1:js:1"),
        !html.contains("$scalive-unresolved-binding")
      )
    },
    test("ordinary JS pushes retain their static message") {
      val compiled = RenderProgram.compile[Unit, String](_ => button(on.click(JS.push("save"))))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        message = candidate.bindings
          .resolve(id).get
          .dispatch(BindingPayload.Params(Map("value" -> "ignored")))
      yield assertTrue(message == Right(BindingDispatch.Owner("save")))
    }
  )
