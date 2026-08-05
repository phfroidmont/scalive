package scalive.docs.pipeline

import zio.test.*

object ExpectedCompilationFailuresSpec extends ZIOSpecDefault:
  override def spec = suite("ExpectedCompilationFailuresSpec")(
    test("captures one focused diagnostic from an expected type-check failure") {
      val failure = ExpectedCompilationFailures.CounterWrongModel
      assertTrue(
        failure.id == "counter-wrong-model",
        failure.source.contains("LiveIO[Int]"),
        failure.source.contains("ZIO.succeed(\"zero\")"),
        failure.diagnostic.contains("String"),
        failure.diagnostic.contains("Int"),
        !failure.diagnostic.contains("CounterWrongModelSource")
      )
    }
  )
end ExpectedCompilationFailuresSpec
