package scalive

import scala.compiletime.testing.typeCheckErrors

import zio.test.*

object SignalSpec extends ZIOSpecDefault:

  override def spec = suite("SignalSpec")(
    test("memoizes a derived signal once per evaluation") {
      val scope  = SignalScope.root()
      val source = Signal.source[Int](scope)
      var calls  = 0
      val derived = source.map { value =>
        calls += 1
        value * 2
      }

      val evaluation = SignalEvaluation.begin(
        previous = SignalEvaluation.empty,
        revision = 1L,
        sources = Map(source -> 2)
      )
      val first  = evaluation.sample(derived)
      val second = evaluation.sample(derived)

      assertTrue(first.value == 4, second.value == 4, calls == 1)
    },
    test("preserves a derived revision when its projected value is equal") {
      val scope   = SignalScope.root()
      val source  = Signal.source[Int](scope)
      val parity  = source.map(_ % 2)
      val initial = SignalEvaluation.begin(
        previous = SignalEvaluation.empty,
        revision = 1L,
        sources = Map(source -> 1)
      )
      val initialParity = initial.sample(parity)
      val committed     = initial.commit()

      val next = SignalEvaluation.begin(
        previous = committed,
        revision = 2L,
        sources = Map(source -> 3)
      )
      val nextParity = next.sample(parity)

      assertTrue(
        initialParity.value == 1,
        nextParity.value == 1,
        nextParity.revision == initialParity.revision
      )
    },
    test("does not recompute a projection when its dependency revision is unchanged") {
      val scope  = SignalScope.root()
      val source = Signal.source[Int](scope)
      var calls  = 0
      val derived = source.map { value =>
        calls += 1
        value.toString
      }

      val initial = SignalEvaluation.begin(
        previous = SignalEvaluation.empty,
        revision = 1L,
        sources = Map(source -> 1)
      )
      val _ = initial.sample(derived)

      val next = SignalEvaluation.begin(
        previous = initial.commit(),
        revision = 2L,
        sources = Map(source -> 1)
      )
      val _ = next.sample(derived)

      assertTrue(calls == 1)
    },
    test("allows ancestor signals in child scopes and rejects escaping child signals") {
      val root        = SignalScope.root()
      val child       = root.child()
      val sibling     = root.child()
      val rootSignal  = Signal.source[Int](root)
      val childSignal = Signal.source[Int](child)

      val ancestorRead = child.validate(rootSignal)
      val parentEscape = root.validate(childSignal)
      val siblingRead  = sibling.validate(childSignal)

      assertTrue(
        ancestorRead.isRight,
        parentEscape.isLeft,
        siblingRead.isLeft
      )
    },
    test("rejects zipping signals from sibling scopes") {
      val root  = SignalScope.root()
      val left  = Signal.source[Int](root.child())
      val right = Signal.source[Int](root.child())

      assertTrue(
        scala.util.Try(left.zip(right)).failed.toOption.exists(
          _.isInstanceOf[IllegalArgumentException]
        )
      )
    },
    test("rejects child creation, validation, and evaluation after scope disposal") {
      val scope  = SignalScope.root()
      val source = Signal.source[Int](scope)
      scope.dispose()

      val childFailure = scala.util.Try(scope.child()).failed.toOption
      val sampleFailure = scala.util.Try(
        SignalEvaluation
          .begin(SignalEvaluation.empty, 1L, Map(source -> 1)).sample(source)
      ).failed.toOption

      assertTrue(
        scope.validate(source).isLeft,
        childFailure.exists(_.isInstanceOf[IllegalStateException]),
        sampleFailure.exists(_.isInstanceOf[IllegalStateException])
      )
    },
    test("does not expose signal sampling or mutation") {
      val errors = typeCheckErrors("""
        import scalive.*

        def invalid(signal: Signal[Int]) =
          signal.now
          signal.set(1)
      """)

      assertTrue(errors.size == 2)
    }
  )
end SignalSpec
