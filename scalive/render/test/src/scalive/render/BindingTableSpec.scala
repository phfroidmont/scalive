package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object BindingTableSpec extends ZIOSpecDefault:
  override def spec = suite("BindingTableSpec")(
    test("rejects every duplicate binding insertion") {
      val builder   = BindingTable.Builder[Int]()
      val id        = BindingId.fromEncoded("duplicate")
      val operation = BindingOperation[Int](_ => 1)
      val first     = builder.add(id, operation)
      val duplicate = builder.add(id, operation)

      assertTrue(first.isRight, duplicate == Left(RenderError.DuplicateBinding(id)))
    },
    test("namespaces bindings to the owning render program") {
      val firstProgram  = RenderProgram.compile[Unit, Int](_ => button(on.click(1)))
      val secondProgram = RenderProgram.compile[Unit, Int](_ => button(on.click(2)))

      for
        first         <- ZIO.fromEither(firstProgram)
        second        <- ZIO.fromEither(secondProgram)
        firstRender   <- first.evaluate(())
        secondRender  <- second.evaluate(())
        oldId = firstRender.bindings.ids.head
        replacementId = secondRender.bindings.ids.head
      yield assertTrue(
        oldId != replacementId,
        secondRender.bindings.resolve(BindingId.fromEncoded(oldId.encoded)).isEmpty
      )
    },
    test("decodes browser payload inside the typed operation") {
      val compiled = RenderProgram.compile[Unit, Int] { _ =>
        button(on.click((params: Map[String, String]) => params("value").toInt), "submit")
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        result = candidate.bindings
          .resolve(BindingId.fromEncoded(id.encoded)).get
          .dispatch(BindingPayload.Params(Map("value" -> "42")))
      yield assertTrue(result == Right(BindingDispatch.Owner(42)))
    },
    test("signal bindings retain the committed rendered value") {
      val compiled = RenderProgram.compile[Int, Int] { model =>
        button(on.click(model)((value, _) => value), "value")
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        firstCommitted = first.commit
        second <- program.evaluate(2, Some(firstCommitted))
        id = first.bindings.ids.head
        payload = BindingPayload.Params(Map.empty)
        committedResult = firstCommitted.bindings.resolve(id).get.dispatch(payload)
        candidateResult = second.bindings.resolve(id).get.dispatch(payload)
      yield assertTrue(
        committedResult == Right(BindingDispatch.Owner(1)),
        candidateResult == Right(BindingDispatch.Owner(2))
      )
    },
    test("registers typed JS push messages through checked insertion") {
      val compiled = RenderProgram.compile[Unit, String] { _ =>
        button(on.click(JS.push("save")), "save")
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        result = candidate.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(
        id.encoded.endsWith(":1:js:0"),
        result == Right(BindingDispatch.Owner("save")),
        HtmlRenderer.render(candidate.tree).contains("$scalive-unresolved-binding") == false
      )
    }
  )
