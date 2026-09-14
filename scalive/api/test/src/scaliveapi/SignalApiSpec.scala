package scaliveapi

import zio.test.*

object SignalApiSpec extends ZIOSpecDefault:

  override def spec = suite("SignalApiSpec")(
    test("combine preserves heterogeneous and tuple-valued inputs") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val pair: Signal[(Boolean, Long)] = null.asInstanceOf[Signal[(Boolean, Long)]]
        val combined: Signal[(Int, String, (Boolean, Long))] =
          Signal.combine((number, text, pair))
        val single: Signal[Tuple1[(Boolean, Long)]] = Signal.combine(Tuple1(pair))
        val projectedSingle: Signal[Long] = Signal.combineWithFn(Tuple1(pair))(p => p._1._2)

        def generic[A, B](a: Signal[A], b: Signal[B]): Signal[(A, B)] =
          Signal.combine((a, b))
        def genericTuple[A <: Tuple](a: Signal[A]): Signal[Tuple1[A]] =
          Signal.combine(Tuple1(a))
      """)

      assertTrue(errors.isEmpty)
    },
    test("combine accepts singleton signal value types") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val one: Signal[1] = null.asInstanceOf[Signal[1]]
        val word: Signal["word"] = null.asInstanceOf[Signal["word"]]
        val combined: Signal[(1, "word")] = Signal.combine((one, word))
      """)

      assertTrue(errors.isEmpty)
    },
    test("combine accepts stable signal object singleton types") {
      val stableObjectErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val inputs: (number.type, text.type) = (number, text)
        val combined: Signal[(Int, String)] = Signal.combine(inputs)
      """)

      assertTrue(stableObjectErrors.isEmpty)
    },
    test("combineWithFn supports direct multi-parameter lambdas") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val flag: Signal[Boolean] = null.asInstanceOf[Signal[Boolean]]
        val result: Signal[String] =
          Signal.combineWithFn((number, text, flag))((n, s, b) => s"$n:$s:$b")

        val prebound: ((Int, String, Boolean)) => String = values => values._2
        val fromPrebound: Signal[String] =
          Signal.combineWithFn((number, text, flag))(prebound)

        val direct: (Int, String, Boolean) => String = (n, s, b) => s"$n:$s:$b"
        val fromTupled: Signal[String] =
          Signal.combineWithFn((number, text, flag))(direct.tupled)

        def combineAll[Signals <: NonEmptyTuple](signals: Signals)(using
            Tuple.Union[Signals] <:< Signal[?]
        ): Signal[Tuple.InverseMap[Signals, Signal]] = Signal.combine(signals)
      """)

      assertTrue(errors.isEmpty)
    },
    test("combine and combineWithFn support more than 22 inputs") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val i: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val s: Signal[String] = null.asInstanceOf[Signal[String]]
        val b: Signal[Boolean] = null.asInstanceOf[Signal[Boolean]]
        val signals = (i, s, b, i, s, b, i, s, b, i, s, b,
          i, s, b, i, s, b, i, s, b, i, s, b)
        val combined = Signal.combine(signals)
        val result: Signal[String] = Signal.combineWithFn(signals)(
          (a01, a02, a03, a04, a05, a06, a07, a08, a09, a10, a11, a12,
           a13, a14, a15, a16, a17, a18, a19, a20, a21, a22, a23, a24) =>
            val first: Int = a01
            val last: Boolean = a24
            s"$first:$a02:$last"
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("combine supports bottom-valued signal inputs") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val first: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val second: Signal[Nothing] = null.asInstanceOf[Signal[Nothing]]
        val third: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val combined: Signal[(Int, Nothing, Int)] = Signal.combine((first, second, third))
        val f: (Int, Nothing, Int) => Int = (a, _, c) => a + c
        val projected: Signal[Int] =
          Signal.combineWithFn((first, second, third))(f.tupled)
      """)
      val outputErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val first: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val second: Signal[Nothing] = null.asInstanceOf[Signal[Nothing]]
        val third: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val wrong: Signal[(String, Nothing, Int)] = Signal.combine((first, second, third))
      """)

      assertTrue(errors.isEmpty, outputErrors.nonEmpty)
    },
    test("combineWith shallowly concatenates outer tuples") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        final case class Account(id: Int)
        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val tuple: Signal[(Boolean, (Long, Double))] =
          null.asInstanceOf[Signal[(Boolean, (Long, Double))]]
        val account: Signal[Account] = null.asInstanceOf[Signal[Account]]

        val scalarScalar: Signal[(Int, String)] = number.combineWith(text)
        val explicitTypeArgument: Signal[(Int, String)] = number.combineWith[String](text)
        val scalarTuple: Signal[(Int, Boolean, (Long, Double))] = number.combineWith(tuple)
        val tupleScalar: Signal[(Boolean, (Long, Double), String)] = tuple.combineWith(text)
        val tupleTuple: Signal[(Boolean, (Long, Double), Boolean, (Long, Double))] =
          tuple.combineWith(tuple)
        val chained: Signal[(Int, String, Boolean, (Long, Double), Account)] =
          number.combineWith(text).combineWith(tuple).combineWith(account)
      """)

      assertTrue(errors.isEmpty)
    },
    test("binary combineWith accepts a bottom-valued signal call") {
      val callErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val bottom: Signal[Nothing] = null.asInstanceOf[Signal[Nothing]]
        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val combined = bottom.combineWith(number)
      """)
      val outputErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val bottom: Signal[Nothing] = null.asInstanceOf[Signal[Nothing]]
        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val combined: Signal[(Nothing, Int)] = bottom.combineWith(number)
      """)

      assertTrue(callErrors.isEmpty, outputErrors.nonEmpty)
    },
    test("combineWith is sound for widened and generic signal values") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def combineScalars[A, B](left: Signal[A], right: Signal[B]): Signal[(A, B)] =
          left.combineWith(right)
        def combineTuples[A <: Tuple, B <: Tuple](
            left: Signal[A], right: Signal[B]
        ): Signal[Tuple.Concat[A, B]] = left.combineWith(right)
        def combineTupleAndScalar[A <: Tuple, B](
            left: Signal[A], right: Signal[B]
        ): Signal[Tuple.Concat[A, Tuple1[B]]] = left.combineWith(right)
        def combineScalarAndTuple[A, B <: Tuple](
            left: Signal[A], right: Signal[B]
        ): Signal[Tuple.Concat[Tuple1[A], B]] = left.combineWith(right)

        val widened: Signal[Any] = null.asInstanceOf[Signal[Any]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val preserved: Signal[(Any, String)] = widened.combineWith(text)
      """)

      assertTrue(errors.isEmpty)
    },
    test("instance combineWithFn passes original values unflattened") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val left: Signal[(Int, String)] = null.asInstanceOf[Signal[(Int, String)]]
        val right: Signal[(Boolean, Long)] = null.asInstanceOf[Signal[(Boolean, Long)]]
        val result: Signal[String] =
          left.combineWithFn(right)((a: (Int, String), b: (Boolean, Long)) => a._2)
      """)

      assertTrue(errors.isEmpty)
    },
    test("combine rejects empty tuples and non-signals") {
      val emptyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        Signal.combine(EmptyTuple)
      """)
      val nonSignalErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        Signal.combine((1, "not a signal"))
      """)
      val mixedErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val signal: Signal[Int] = null.asInstanceOf[Signal[Int]]
        Signal.combine((signal, "not a signal"))
      """)
      val functionErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        Signal.combineWithFn((1, "not a signal"))((a, b) => a)
      """)

      assertTrue(
        emptyErrors.nonEmpty,
        nonSignalErrors.exists(_.message.contains("Signal")),
        mixedErrors.exists(_.message.contains("Signal")),
        functionErrors.exists(_.message.contains("Signal"))
      )
    },
    test("combineWithFn rejects incompatible function shapes") {
      val arityErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        Signal.combineWithFn((number, text))((n, s, extra) => n)
      """)
      val outputErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val number: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val text: Signal[String] = null.asInstanceOf[Signal[String]]
        val result: Signal[Int] = Signal.combineWithFn((number, text))((n, s) => s)
      """)

      assertTrue(arityErrors.nonEmpty, outputErrors.nonEmpty)
    },
    test("zip is no longer public") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val left: Signal[Int] = null.asInstanceOf[Signal[Int]]
        val right: Signal[String] = null.asInstanceOf[Signal[String]]
        left.zip(right)
      """)

      assertTrue(errors.exists(_.message.contains("zip")))
    }
  )
end SignalApiSpec
