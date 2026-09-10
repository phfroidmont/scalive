package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object SignalEvaluationSpec extends ZIOSpecDefault:
  final class EqualFunction(prefix: String) extends (Int => String):
    def apply(value: Int): String = s"$prefix$value"
    override def hashCode(): Int  = 1
    override def equals(other: Any): Boolean = other.isInstanceOf[EqualFunction]

  private def sourceAndRevision(): (Signal[Int], RenderRevision) =
    val scope    = SignalScope.root()
    val source   = Signal.source[Int](SignalSource[Int](scope))
    val revision = RenderRevision.next(RenderRevision.initial).toOption.get
    (source, revision)

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
    test("evaluates and validates deep map chains without consuming the call stack") {
      val (source, revision) = sourceAndRevision()
      var mapped             = source
      var index              = 0
      while index < 50000 do
        mapped = mapped.map(_ + 1)
        index += 1

      val owner       = SignalEvaluation.scopeOf(mapped)
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 0)
      val sampled     = transaction.sample(mapped)

      assertTrue(owner.exists(_ eq SignalEvaluation.scopeOf(source).toOption.get)) &&
      assertTrue(sampled.exists(_.value == 50000))
    },
    test("evaluates and validates deep zip chains with scalar outputs") {
      val (source, revision) = sourceAndRevision()
      var zipped             = source
      var index              = 0
      while index < 20000 do
        zipped = zipped.zip(source).map { case (left, _) => left + 1 }
        index += 1

      val owner       = SignalEvaluation.scopeOf(zipped)
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 0)
      val sampled     = transaction.sample(zipped)

      assertTrue(owner.isRight, sampled.exists(_.value == 20000))
    },
    test("memoizes shared signal DAGs while validating scope and evaluating") {
      val (source, revision) = sourceAndRevision()
      var shared             = source
      var index              = 0
      var calls              = 0
      while index < 64 do
        shared = shared.zip(shared).map { case (left, _) =>
          calls += 1
          left
        }
        index += 1

      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 1)
      val sampled     = transaction.sample(shared)
      val nextRevision = RenderRevision.next(revision).toOption.get
      val nextTransaction = SignalEvaluation.begin(transaction.result, nextRevision, source, 1)
      val resampled = nextTransaction.sample(shared)

      assertTrue(
        SignalEvaluation.scopeOf(shared).isRight,
        sampled.exists(_.value == 1),
        resampled == sampled,
        calls == 64
      )
    },
    test("does not reevaluate downstream maps when an intermediate output is unchanged") {
      val (source, firstRevision) = sourceAndRevision()
      var intermediateCalls = 0
      var downstreamCalls   = 0
      val intermediate = source.map { value =>
        intermediateCalls += 1
        value % 2
      }
      val downstream = intermediate.map { value =>
        downstreamCalls += 1
        value.toString
      }

      val firstTransaction =
        SignalEvaluation.begin(SignalEvaluation.empty, firstRevision, source, 1)
      val firstIntermediate = firstTransaction.sample(intermediate).toOption.get
      val firstDownstream   = firstTransaction.sample(downstream).toOption.get
      val secondRevision    = RenderRevision.next(firstRevision).toOption.get
      val secondTransaction =
        SignalEvaluation.begin(firstTransaction.result, secondRevision, source, 3)
      val secondIntermediate = secondTransaction.sample(intermediate).toOption.get
      val secondDownstream   = secondTransaction.sample(downstream).toOption.get

      assertTrue(
        intermediateCalls == 2,
        downstreamCalls == 1,
        secondIntermediate.revision == firstIntermediate.revision,
        secondIntermediate.dependencyRevisions == Vector(secondRevision),
        secondDownstream == firstDownstream
      )
    },
    test("evaluates zipped dependencies left before right and stops after a failure") {
      val (source, revision) = sourceAndRevision()
      var evaluated          = Vector.empty[String]
      val left = source.map { _ =>
        evaluated :+= "left"
        throw new IllegalStateException("left failed")
      }
      val right = source.map { value =>
        evaluated :+= "right"
        value
      }
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 1)

      assertTrue(transaction.sample(left.zip(right)).isLeft, evaluated == Vector("left"))
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
