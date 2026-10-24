package scalive.benchmarks

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}

/** Checks the allocation of every benchmark and the heap retained per row against a baseline.
  *
  * Allocation is JMH's `gc.alloc.rate.norm`, in bytes per operation, at 1,000 rows and 100 cards.
  * Unlike time, it barely depends on the machine or its load, so it can gate a build. The retained
  * heap comes from [[RetainedHeap]], in bytes per row.
  *
  * `check` fails when a measurement exceeds its baseline by more than its tolerance, or when the
  * measurements and the baseline do not name the same things. `update` writes the measurements as
  * the new baseline. Run them with `mill benchmarks.checkRegressions` and
  * `mill benchmarks.updateRegressionBaseline`.
  */
object RegressionGate:
  // Allocation varies a little with JIT decisions; the retained heap is exact.
  private val AllocationTolerance = 0.10
  private val AllocationSlack     = 256L
  private val RetainedTolerance   = 0.02

  private val Header =
    """# Regression baseline of `mill benchmarks.checkRegressions`.
      |# `alloc` lines: JMH gc.alloc.rate.norm in B/op, tolerance 10% + 256 B.
      |# `retained` lines: heap retained per row in B, tolerance 2%.
      |# Regenerate with `mill benchmarks.updateRegressionBaseline` after an intended change.
      |""".stripMargin

  def main(args: Array[String]): Unit = args.toList match
    case List("check", baseline)  => check(Path.of(baseline))
    case List("update", baseline) => update(Path.of(baseline))
    case _                        =>
      System.err.println("usage: RegressionGate check|update <baseline file>")
      sys.exit(2)

  private def check(baselineFile: Path): Unit =
    val baseline = read(baselineFile)
    val measured = measure()
    val missing  = baseline.keySet -- measured.keySet
    val unknown  = measured.keySet -- baseline.keySet

    val regressions = measured.toVector.sortBy(_._1).flatMap { case (name, bytes) =>
      baseline.get(name).flatMap { expected =>
        val limit = allowed(name, expected)
        val ratio = if expected == 0 then 0.0 else bytes.toDouble / expected - 1
        println(f"$name%-90s $expected%12d → $bytes%12d (${ratio * 100}%+6.1f%%)")
        Option.when(bytes > limit)(s"$name: $bytes B, baseline $expected B, allowed up to $limit B")
      }
    }
    val failures = regressions ++
      missing.toVector.sorted.map(name => s"$name: in the baseline but no longer measured") ++
      unknown.toVector.sorted.map(name => s"$name: measured but not in the baseline")

    if failures.isEmpty then println("No allocation or retained-heap regression.")
    else
      System.err.println("Regressions against the baseline:")
      failures.foreach(failure => System.err.println(s"  $failure"))
      System.err.println(
        "Fix them, or run `mill benchmarks.updateRegressionBaseline` if they are intended."
      )
      sys.exit(1)

  private def update(baselineFile: Path): Unit =
    val lines = measure().toVector.sortBy(_._1).map((name, bytes) => s"$name $bytes")
    Files.writeString(baselineFile, Header + lines.mkString("", "\n", "\n")): Unit
    println(s"Wrote ${lines.size} measurements to $baselineFile")

  private def allowed(name: String, expected: Long): Long =
    if name.startsWith("retained ") then Math.round(expected * (1 + RetainedTolerance))
    else Math.round(expected * (1 + AllocationTolerance)) + AllocationSlack

  private def read(file: Path): Map[String, Long] =
    Files
      .readAllLines(file).asScala
      .filterNot(line => line.isBlank || line.startsWith("#"))
      .map { line =>
        val split = line.lastIndexOf(' ')
        line.take(split) -> line.drop(split + 1).toLong
      }.toMap

  private def measure(): Map[String, Long] =
    val retained = RetainedHeap.measure().map((name, bytes) => s"retained $name" -> bytes)
    allocations() ++ retained

  private def allocations(): Map[String, Long] =
    val options = OptionsBuilder()
      .include("scalive\\.benchmarks\\..*")
      .param("rowCount", "1000")
      .param("cardCount", "100")
      .addProfiler(classOf[GCProfiler])
      .forks(1)
      .warmupIterations(3)
      .warmupTime(TimeValue.milliseconds(500))
      .measurementIterations(3)
      .measurementTime(TimeValue.milliseconds(500))
      .timeUnit(TimeUnit.MICROSECONDS)
      .shouldFailOnError(true)
      .build()
    Runner(options).run().asScala.map(result => name(result) -> allocation(result)).toMap

  // For example `alloc KeyedRowsBenchmark.semanticDiff operation=update rowCount=1000`.
  private def name(result: RunResult): String =
    val params    = result.getParams
    val benchmark = params.getBenchmark.stripPrefix("scalive.benchmarks.")
    val values    =
      params.getParamsKeys.asScala.toVector.sorted.map(key => s"$key=${params.getParam(key)}")
    (Vector("alloc", benchmark) ++ values).mkString(" ")

  private def allocation(result: RunResult): Long =
    Math.round(result.getSecondaryResults.get("gc.alloc.rate.norm").getScore)
end RegressionGate
