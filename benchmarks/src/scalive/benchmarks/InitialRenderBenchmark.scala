package scalive.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import scalive.*
import scalive.protocol.phoenix.PhoenixRenderedEncoder
import scalive.render.*

object InitialRenderBenchmark:
  final case class Row(id: Int, label: String, group: Int)

@State(Scope.Benchmark)
class InitialRenderState:
  import BenchmarkSupport.*
  import InitialRenderBenchmark.*

  @Param(Array("1000", "10000"))
  var rowCount: Int = 0

  var rows: Vector[Row]                           = Vector.empty
  var program: RenderProgram[Vector[Row], String] = null
  var tree: EvaluatedTree                         = null

  private def view(model: Signal[Vector[Row]]): HtmlElement[String] =
    table(
      tbody(
        model.splitBy(_.id) { (id, row) =>
          tr(
            dataAttr("row-id") := id.toString,
            td(row.map(_.label)),
            td(row.map(_.group.toString)),
            td(button(on.click(s"select-$id"), "select"))
          )
        }
      )
    )

  @Setup(Level.Trial)
  def setup(): Unit =
    rows = Vector.tabulate(rowCount)(i => Row(i, s"row-$i", i % 17))
    program = RenderProgram.compile[Vector[Row], String](view).fold(throw _, identity)
    val candidate = run(program.evaluate(rows))
    tree = candidate.tree
    run(candidate.discard)

  @TearDown(Level.Trial)
  def close(): Unit = run(program.close)
end InitialRenderState

/** The first render of a page holding a list, as a mount makes it. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class InitialRenderBenchmark:
  import BenchmarkSupport.*

  @Benchmark
  def evaluate(state: InitialRenderState, blackhole: Blackhole): Unit =
    val tree = run(
      state.program
        .evaluate(state.rows).flatMap(candidate => candidate.discard.as(candidate.tree))
    )
    blackhole.consume(tree)

  @Benchmark
  def phoenixEncoding(state: InitialRenderState, blackhole: Blackhole): Unit =
    blackhole.consume(
      PhoenixRenderedEncoder
        .initial(state.tree)
        .fold(error => throw RuntimeException(error.toString), identity)
    )

  @Benchmark
  def html(state: InitialRenderState, blackhole: Blackhole): Unit =
    blackhole.consume(
      PhoenixRenderedEncoder
        .fullHtml(state.tree)
        .fold(error => throw RuntimeException(error.toString), identity)
    )
end InitialRenderBenchmark
