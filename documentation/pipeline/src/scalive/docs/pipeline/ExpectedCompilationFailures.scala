package scalive.docs.pipeline

import scala.compiletime.testing.typeCheckErrors

import scalive.docs.model.CompilationFailure

private[pipeline] object ExpectedCompilationFailures:
  private val CounterWrongModelSource =
    """import scalive.LiveIO
      |import zio.ZIO
      |val initialCount: LiveIO[Int] = ZIO.succeed("zero")""".stripMargin

  private val counterWrongModelErrors = typeCheckErrors(
    """import scalive.LiveIO
import zio.ZIO
val initialCount: LiveIO[Int] = ZIO.succeed("zero")"""
  )

  val CounterWrongModel = CompilationFailure(
    id = "counter-wrong-model",
    source = CounterWrongModelSource,
    diagnostic = focusedDiagnostic(counterWrongModelErrors.map(_.message))
  )

  def forExample(id: String): Vector[CompilationFailure] = id match
    case "counter" => Vector(CounterWrongModel)
    case _         => Vector.empty

  private def focusedDiagnostic(messages: List[String]): String =
    val relevant = messages.filter(message => message.contains("String") && message.contains("Int"))
    if relevant.size != 1 then
      throw new IllegalStateException("Expected one focused counter compilation failure.")
    relevant.head.linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" ")
