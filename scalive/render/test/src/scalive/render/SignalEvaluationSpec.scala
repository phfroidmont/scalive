package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object SignalEvaluationSpec extends ZIOSpecDefault:
  final case class Atomic(left: Int, right: String)

  final class EqualFunction(prefix: String) extends (Int => String):
    def apply(value: Int): String            = s"$prefix$value"
    override def hashCode(): Int             = 1
    override def equals(other: Any): Boolean = other.isInstanceOf[EqualFunction]

  private def sourceAndRevision(): (Signal[Int], RenderRevision) =
    val scope    = SignalScope.root()
    val source   = Signal.source[Int](SignalSource[Int](scope))
    val revision = RenderRevision.next(RenderRevision.initial).toOption.get
    (source, revision)

  private def combine[A, B](left: Signal[A], right: Signal[B]): Signal[(A, B)] =
    left.combineWith(right)

  override def spec = suite("SignalEvaluationSpec")(
    test("samples a derived signal once and reuses unchanged dependency revisions") {
      var calls    = 0
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
    test("evaluates combined root signals exactly once") {
      var calls    = 0
      val compiled = RenderProgram.compile[Int, Nothing] { model =>
        val mapped = model.map { value =>
          calls += 1
          value * 2
        }
        div(mapped.combineWithFn(mapped)((left, right) => s"$left:$right"))
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(2)
      yield assertTrue(calls == 1, HtmlRenderer.render(candidate.tree) == "<div>4:4</div>")
    },
    test("combineWith shallowly concatenates tuple operands") {
      val (source, revision) = sourceAndRevision()
      val number             = source.map(identity)
      val text               = source.map(value => s"n=$value")
      val pair               = source.map(value => (value, value.toString))
      val nested             = source.map(value => ((value, value.toString), value > 0))
      val atomic             = source.map(value => Atomic(value, value.toString))
      val empty              = source.map(_ => EmptyTuple)

      val scalarScalar: Signal[(Int, String)]         = number.combineWith(text)
      val tupleScalar: Signal[(Int, String, Boolean)] = pair.combineWith(source.map(_ > 0))
      val scalarTuple: Signal[(Boolean, Int, String)] = source.map(_ > 0).combineWith(pair)
      val tupleTuple: Signal[(Int, String, Boolean, Int, String)] =
        pair.combineWith(source.map(value => (value > 0, value, value.toString)))
      val chained: Signal[(Int, String, Boolean)] =
        number.combineWith(text).combineWith(source.map(_ > 0))
      val nestedTuple: Signal[((Int, String), Boolean, Atomic)] = nested.combineWith(atomic)
      val emptyRight: Signal[Tuple1[Int]]                       = number.combineWith(empty)
      val emptyLeft: Signal[Tuple1[String]]                     = empty.combineWith(text)

      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 2)

      assertTrue(
        transaction.sample(scalarScalar).exists(_.value == (2, "n=2")),
        transaction.sample(tupleScalar).exists(_.value == (2, "2", true)),
        transaction.sample(scalarTuple).exists(_.value == (true, 2, "2")),
        transaction.sample(tupleTuple).exists(_.value == (2, "2", true, 2, "2")),
        transaction.sample(chained).exists(_.value == (2, "n=2", true)),
        transaction.sample(nestedTuple).exists(_.value == ((2, "2"), true, Atomic(2, "2"))),
        transaction.sample(emptyRight).exists(_.value == Tuple1(2)),
        transaction.sample(emptyLeft).exists(_.value == Tuple1("n=2"))
      )
    },
    test("companion combine preserves each signal value as one tuple slot") {
      val (source, revision)        = sourceAndRevision()
      val pair                      = source.map(value => (value, value.toString))
      val flag                      = source.map(_ > 0)
      val broadlyTyped: Signal[Any] = pair

      val grouped: Signal[((Int, String), Boolean)] = Signal.combine((pair, flag))
      val singleton: Signal[Tuple1[(Int, String)]]  = Signal.combine(Tuple1(pair))
      val broad: Signal[(Any, Boolean)]             = Signal.combine((broadlyTyped, flag))
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 3)

      assertTrue(
        transaction.sample(grouped).exists(_.value == ((3, "3"), true)),
        transaction.sample(singleton).exists(_.value == Tuple1((3, "3"))),
        transaction.sample(broad).exists(_.value == ((3, "3"), true))
      )
    },
    test("binary combineWith treats broadly and generically typed tuple values as atomic") {
      val (source, revision)      = sourceAndRevision()
      val pair                    = source.map(value => (value, value.toString))
      val otherPair               = source.map(value => (value > 0, value.toDouble))
      val broadLeft: Signal[Any]  = pair
      val broadRight: Signal[Any] = otherPair

      val broad: Signal[(Any, Any)]                           = broadLeft.combineWith(broadRight)
      val generic: Signal[((Int, String), (Boolean, Double))] = combine(pair, otherPair)
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 5)

      assertTrue(
        transaction.sample(broad).exists(_.value == ((5, "5"), (true, 5.0))),
        transaction.sample(generic).exists(_.value == ((5, "5"), (true, 5.0)))
      )
    },
    test("combineWithFn receives original grouped values while companion arguments are direct") {
      val (source, revision) = sourceAndRevision()
      val pair               = source.map(value => (value, value.toString))
      val flag               = source.map(_ > 0)
      val extension          = pair.combineWithFn(flag) { (group, enabled) =>
        s"${group._1}:${group._2}:$enabled"
      }
      val companion = Signal.combineWithFn((pair, flag)) { (group, enabled) =>
        s"${group._1}:${group._2}:$enabled"
      }
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 4)

      assertTrue(
        transaction.sample(extension).exists(_.value == "4:4:true"),
        transaction.sample(companion).exists(_.value == "4:4:true")
      )
    },
    test("recomputes a combination when either independently derived value changes") {
      var calls    = 0
      val compiled = RenderProgram.compile[(Int, String), Nothing] { model =>
        val left  = model.map(_._1)
        val right = model.map(_._2)
        div(left.combineWithFn(right) { (number, text) =>
          calls += 1
          s"$number:$text"
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1 -> "a")
        second  <- program.evaluate(2 -> "a", Some(first.commit))
        third   <- program.evaluate(2 -> "b", Some(second.commit))
      yield assertTrue(
        calls == 3,
        HtmlRenderer.render(first.tree) == "<div>1:a</div>",
        HtmlRenderer.render(second.tree) == "<div>2:a</div>",
        HtmlRenderer.render(third.tree) == "<div>2:b</div>"
      )
    },
    test("multi-input combineWithFn tracks each value, shared inputs, and unchanged output") {
      var combinedCalls   = 0
      var sharedCalls     = 0
      var stableCalls     = 0
      var downstreamCalls = 0
      val compiled        = RenderProgram.compile[(Int, String, Boolean), Nothing] { model =>
        val number = model.map(_._1)
        val text   = model.map(_._2)
        val flag   = model.map(_._3)
        val shared = number.map { value =>
          sharedCalls += 1
          value * 10
        }
        val combined = Signal.combineWithFn((number, text, flag, shared, shared)) {
          (currentNumber, currentText, currentFlag, firstShared, secondShared) =>
            combinedCalls += 1
            s"$currentNumber:$currentText:$currentFlag:$firstShared:$secondShared"
        }
        val stable = Signal
          .combineWithFn((number.map(_ % 2), text.map(_.length), flag)) { (_, _, _) =>
            stableCalls += 1
            "stable"
          }.map { value =>
            downstreamCalls += 1
            value
          }
        div(combined, stable)
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate((1, "a", false))
        second  <- program.evaluate((2, "a", false), Some(first.commit))
        third   <- program.evaluate((2, "bb", false), Some(second.commit))
        fourth  <- program.evaluate((2, "bb", true), Some(third.commit))
      yield assertTrue(
        combinedCalls == 4,
        sharedCalls == 2,
        stableCalls == 4,
        downstreamCalls == 1,
        HtmlRenderer.render(fourth.tree) == "<div>2:bb:true:20:20stable</div>"
      )
    },
    test("companion combination evaluates inputs left-to-right and is lazy") {
      val (source, revision) = sourceAndRevision()
      var evaluated          = Vector.empty[String]
      var combinedCalls      = 0
      val first              = source.map { value =>
        evaluated :+= "first"
        value
      }
      val second = source.map[Int] { _ =>
        evaluated :+= "second"
        throw new IllegalStateException("second failed")
      }
      val third = source.map { value =>
        evaluated :+= "third"
        value
      }
      val combined = Signal.combineWithFn((first, second, third)) { (_, _, _) =>
        combinedCalls += 1
        "unreachable"
      }
      val callsBeforeEvaluation = combinedCalls
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 1)

      assertTrue(
        callsBeforeEvaluation == 0,
        transaction.sample(combined).isLeft,
        evaluated == Vector("first", "second"),
        combinedCalls == 0
      )
    },
    test("companion combination validates ancestry, sibling scopes, and closed scopes") {
      val root       = SignalScope.root()
      val child      = root.child().toOption.get
      val sibling    = root.child().toOption.get
      val rootSignal = Signal.source[Int](SignalSource[Int](root))
      val childOne   = Signal.source[String](SignalSource[String](child))
      val childTwo   = Signal.source[Boolean](SignalSource[Boolean](child))
      val siblingOne = Signal.source[Double](SignalSource[Double](sibling))

      val ancestry     = SignalEvaluation.scopeOf(Signal.combine((rootSignal, childOne, childTwo)))
      val incompatible =
        SignalEvaluation.scopeOf(Signal.combine((childOne, siblingOne, rootSignal)))
      child.close()
      val closed = child.validate(Signal.combine((rootSignal, childOne, childTwo)))

      assertTrue(
        ancestry.exists(_ eq child),
        incompatible.isLeft,
        closed.isLeft
      )
    },
    test("companion combine evaluates tuples beyond arity 22") {
      val (source, revision) = sourceAndRevision()
      val combined: Signal[
        (
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int,
          Int
        )
      ] =
        Signal.combine(
          (
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source,
            source
          )
        )
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 24)
      val value       = transaction.sample(combined).toOption.get.value

      assertTrue(value.productArity == 24, value(0) == 24, value(23) == 24)
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
    test("evaluates and validates deep binary combination chains with scalar outputs") {
      val (source, revision) = sourceAndRevision()
      var combined           = source
      var index              = 0
      while index < 20000 do
        combined = combined.combineWithFn(source)((left, _) => left + 1)
        index += 1

      val owner       = SignalEvaluation.scopeOf(combined)
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 0)
      val sampled     = transaction.sample(combined)

      assertTrue(owner.isRight, sampled.exists(_.value == 20000))
    },
    test("memoizes shared signal DAGs while validating scope and evaluating") {
      val (source, revision) = sourceAndRevision()
      var shared             = source
      var index              = 0
      var calls              = 0
      while index < 64 do
        shared = shared.combineWithFn(shared) { (left, _) =>
          calls += 1
          left
        }
        index += 1

      val transaction     = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 1)
      val sampled         = transaction.sample(shared)
      val nextRevision    = RenderRevision.next(revision).toOption.get
      val nextTransaction = SignalEvaluation.begin(transaction.result, nextRevision, source, 1)
      val resampled       = nextTransaction.sample(shared)

      assertTrue(
        SignalEvaluation.scopeOf(shared).isRight,
        sampled.exists(_.value == 1),
        resampled == sampled,
        calls == 64
      )
    },
    test("does not reevaluate downstream maps when an intermediate output is unchanged") {
      val (source, firstRevision) = sourceAndRevision()
      var intermediateCalls       = 0
      var downstreamCalls         = 0
      val intermediate            = source.map { value =>
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
    test("evaluates binary combination inputs left before right and stops after a failure") {
      val (source, revision) = sourceAndRevision()
      var evaluated          = Vector.empty[String]
      val left               = source.map { _ =>
        evaluated :+= "left"
        throw new IllegalStateException("left failed")
      }
      val right = source.map { value =>
        evaluated :+= "right"
        value
      }
      val transaction = SignalEvaluation.begin(SignalEvaluation.empty, revision, source, 1)

      val combined = left.combineWithFn(right)((_, value) => value)
      assertTrue(transaction.sample(combined).isLeft, evaluated == Vector("left"))
    },
    test("allows ancestor signals and rejects sibling scope combinations") {
      val root         = SignalScope.root()
      val leftScope    = root.child().toOption.get
      val rightScope   = root.child().toOption.get
      val rootSignal   = Signal.source[Int](SignalSource[Int](root))
      val leftSignal   = Signal.source[Int](SignalSource[Int](leftScope))
      val rightSignal  = Signal.source[Int](SignalSource[Int](rightScope))
      val siblingScope = SignalEvaluation.scopeOf(leftSignal.combineWith(rightSignal))

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
        _      <- program.close
        result <- program.evaluate(1).exit
      yield assertTrue(result.isFailure)
    }
  )
end SignalEvaluationSpec
