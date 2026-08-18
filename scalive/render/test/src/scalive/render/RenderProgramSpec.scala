package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object RenderProgramSpec extends ZIOSpecDefault:
  override def spec = suite("RenderProgramSpec")(
    test("constructs the view once and allocates monotonic identities") {
      var constructions = 0
      val compiled = RenderProgram.compile[Int, Int] { model =>
        constructions += 1
        div(
          dataAttr("count") := model.map(_.toString),
          on.click(model)((value, _) => value),
          span(model.map(_.toString))
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        second  <- program.evaluate(2, Some(first.commit))
        root = first.tree.root
        child = root.children.head.asInstanceOf[EvaluatedNode.Element]
        text = child.children.head.asInstanceOf[EvaluatedNode.Text]
      yield assertTrue(
        constructions == 1,
        root.id.value == 1L,
        child.id.value == 2L,
        text.id.value == 3L,
        root.attributes.head.slot.exists(_.value == 1L),
        first.bindings.ids == second.bindings.ids,
        first.bindings.ids.head.encoded.matches("b[0-9]+:1")
      )
    },
    test("keeps opaque identity types nominal") {
      val templateErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.*
        val id: TemplateId = 1L
      """)
      val slotErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.*
        val id: BindingSlotId = 1L
      """)

      assertTrue(templateErrors.nonEmpty, slotErrors.nonEmpty)
    },
    test("rejects void children and deferred structural content") {
      val voidResult = RenderProgram.compile[Unit, Nothing](_ => input("child"))
      val choiceResult = RenderProgram.compile[Boolean, Nothing] { selected =>
        div(selected.when(span("selected")))
      }
      val keyedResult = RenderProgram.compile[Unit, Nothing] { _ =>
        div(Vector(1).splitBy(identity)((_, value) => span(value.toString)))
      }

      assertTrue(
        voidResult.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        choiceResult.left.exists(_.isInstanceOf[RenderError.Unsupported]),
        keyedResult.left.exists(_.isInstanceOf[RenderError.Unsupported])
      )
    },
    test("rejects duplicate HTML attributes before binding assembly") {
      val staticDuplicate = RenderProgram.compile[Unit, Nothing] { _ =>
        div(cls := "first", cls := "second")
      }
      val bindingDuplicate = RenderProgram.compile[Unit, Int] { _ =>
        button(on.click(1), on.click(2))
      }

      assertTrue(
        staticDuplicate.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        bindingDuplicate.left.exists(_.isInstanceOf[RenderError.InvalidHtml])
      )
    },
    test("validates raw modifier attribute names") {
      val invalid = RenderProgram.compile[Unit, Nothing] { _ =>
        div(Mod.Attr.Static("data-value onclick=alert(1)", "unsafe"))
      }

      assertTrue(invalid.left.exists(_.isInstanceOf[RenderError.InvalidHtml]))
    },
    test("rejects null static and signal scalar values") {
      val staticNull = RenderProgram.compile[Unit, Nothing] { _ =>
        div(null.asInstanceOf[String])
      }
      val dynamicText = RenderProgram.compile[Unit, Nothing] { model =>
        div(model.map(_ => null: String))
      }
      val dynamicAttribute = RenderProgram.compile[Unit, Nothing] { model =>
        div(dataAttr("value") := model.map(_ => null: String))
      }

      for
        textProgram      <- ZIO.fromEither(dynamicText)
        attributeProgram <- ZIO.fromEither(dynamicAttribute)
        textResult       <- textProgram.evaluate(()).exit
        attributeResult  <- attributeProgram.evaluate(()).exit
      yield assertTrue(
        staticNull.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        textResult.isFailure,
        attributeResult.isFailure
      )
    },
    test("closes a failed candidate scope without changing committed state") {
      var finalized = false
      val compiled = RenderProgram.compile[Int, Int] { model =>
        div(
          on.click(model)((value, _) => value),
          model.map { value =>
            if value < 0 then throw IllegalArgumentException("negative")
            value.toString
          }
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        initial   <- program.evaluate(1)
        committed = initial.commit
        evaluation = committed.signalEvaluation
        bindingId = committed.bindings.ids.head
        scope     <- CandidateScope.make
        _         <- scope.addFinalizer(ZIO.succeed { finalized = true })
        failed    <- program.evaluateIn(-1, Some(committed), scope).exit
        bindingResult = committed.bindings
          .resolve(bindingId).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(
        failed.isFailure,
        scope.isClosed,
        finalized,
        HtmlRenderer.render(committed.tree) ==
          s"<div phx-click=\"${bindingId.encoded}\">1</div>",
        committed.signalEvaluation == evaluation,
        bindingResult == Right(1),
        !committed.scope.isClosed
      )
    },
    test("transfers candidate scope ownership exactly once") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        candidate <- program.evaluate(1)
        committed = candidate.commit
        _         <- candidate.discard
        remainedOpen = !committed.scope.isClosed
        secondCommit = scala.util.Try(candidate.commit)
        _            <- committed.close
        closedResult <- program.evaluate(2, Some(committed)).exit
        discarded    <- program.evaluate(3)
        _            <- discarded.discard
        discardedCommit = scala.util.Try(discarded.commit)
      yield assertTrue(
        remainedOpen,
        secondCommit.isFailure,
        committed.scope.isClosed,
        closedResult.isFailure,
        discardedCommit.isFailure
      )
    },
    test("keeps successful candidate resources open until discard") {
      var finalized = false
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        candidate <- program.evaluate(1)
        _         <- candidate.stagedScope.addFinalizer(ZIO.succeed { finalized = true })
        open = !candidate.stagedScope.isClosed && !finalized
        _ <- candidate.discard
      yield assertTrue(open, candidate.stagedScope.isClosed, finalized)
    }
  )
