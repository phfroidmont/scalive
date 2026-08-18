package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object SignalEvaluationSpec extends ZIOSpecDefault:
  final class EqualFunction(prefix: String) extends (Int => String):
    def apply(value: Int): String = s"$prefix$value"
    override def hashCode(): Int  = 1
    override def equals(other: Any): Boolean = other.isInstanceOf[EqualFunction]

  override def spec = suite("SignalEvaluationSpec")(
    test("samples a derived signal once and reuses unchanged dependency revisions") {
      var calls = 0
      val compiled = RenderProgram.compile[Int, Nothing] { model =>
        val rendered = model.map { value =>
          calls += 1
          (value % 2).toString
        }
        div(rendered, rendered)
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        second  <- program.evaluate(3, Some(first.commit))
        third   <- program.evaluate(3, Some(second.commit))
      yield assertTrue(
        calls == 2,
        HtmlRenderer.render(first.tree) == "<div>11</div>",
        TreeDiffer.diff(first.tree, second.tree) == RenderDelta.Empty,
        TreeDiffer.diff(second.tree, third.tree) == RenderDelta.Empty
      )
    },
    test("evaluates zipped root signals exactly once") {
      var calls = 0
      val compiled = RenderProgram.compile[Int, Nothing] { model =>
        val mapped = model.map { value =>
          calls += 1
          value * 2
        }
        div(mapped.zip(mapped).map { case (left, right) => s"$left:$right" })
      }

      for
        program  <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(2)
      yield assertTrue(calls == 1, HtmlRenderer.render(candidate.tree) == "<div>4:4</div>")
    },
    test("keys signal samples by identity rather than transformation equality") {
      val compiled = RenderProgram.compile[Int, Nothing] { model =>
        val first  = model.map(EqualFunction("first:"))
        val second = model.map(EqualFunction("second:"))
        div(first, second)
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(2)
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) == "<div>first:2second:2</div>"
      )
    },
    test("allows ancestor signals and rejects sibling scope combinations") {
      val root         = SignalScope.root()
      val leftScope    = root.child().toOption.get
      val rightScope   = root.child().toOption.get
      val rootSignal   = Signal.source[Int](SignalSource[Int](root))
      val leftSignal   = Signal.source[Int](SignalSource[Int](leftScope))
      val rightSignal  = Signal.source[Int](SignalSource[Int](rightScope))
      val siblingScope = SignalEvaluation.scopeOf(leftSignal.zip(rightSignal))

      assertTrue(
        leftScope.validate(rootSignal).isRight,
        root.validate(leftSignal).isLeft,
        rightScope.validate(leftSignal).isLeft,
        siblingScope.isLeft
      )
    },
    test("rejects evaluation after the program scope closes") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        _       <- program.close
        result  <- program.evaluate(1).exit
      yield assertTrue(result.isFailure)
    }
  )
