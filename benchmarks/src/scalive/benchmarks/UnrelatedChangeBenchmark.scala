package scalive.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import zio.json.*

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*

object UnrelatedChangeBenchmark:
  final case class Row(id: Int, label: String)
  final case class Model(counter: Int, rows: Vector[Row])

@State(Scope.Benchmark)
class UnrelatedChangeState:
  import BenchmarkSupport.*
  import UnrelatedChangeBenchmark.*

  @Param(Array("0", "1000", "10000"))
  var rowCount: Int = 0

  var program: RenderProgram[Model, String] = null
  var previous: CommittedRender[String]     = null
  var next: Model                           = null
  var currentTree: EvaluatedTree            = null
  var delta: RenderDelta                    = null
  var phoenixState: PhoenixRenderedState    = null

  private def view(model: Signal[Model]): HtmlElement[String] =
    div(
      span(model.map(_.counter.toString)),
      table(
        tbody(
          model.map(_.rows).splitBy(_.id) { (id, row) =>
            tr(td(row.map(_.label)), td(button(on.click(s"select-$id"), "select")))
          }
        )
      )
    )

  @Setup(Level.Trial)
  def setup(): Unit =
    val initial = Model(0, Vector.tabulate(rowCount)(i => Row(i, s"row-$i")))
    program = RenderProgram.compile[Model, String](view).fold(throw _, identity)
    previous = run(program.evaluate(initial)).commit
    next = initial.copy(counter = 1)
    val candidate = run(program.evaluate(next, Some(previous)))
    currentTree = candidate.tree
    delta = TreeDiffer.diff(previous.tree, currentTree)
    phoenixState = PhoenixRenderedEncoder
      .initial(previous.tree)
      .fold(error => throw RuntimeException(error.toString), _._1)
    run(candidate.discard)
    require(changeCount(delta) == 1)

  @TearDown(Level.Trial)
  def close(): Unit = run(previous.close *> program.close)
end UnrelatedChangeState

/** A counter changes next to a keyed list that does not: none of it should cost per row. */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class UnrelatedChangeBenchmark:
  import BenchmarkSupport.*

  @Benchmark
  def evaluate(state: UnrelatedChangeState, blackhole: Blackhole): Unit =
    val tree = run(
      state.program
        .evaluate(state.next, Some(state.previous)).flatMap(candidate =>
          candidate.discard.as(candidate.tree)
        )
    )
    blackhole.consume(tree)

  @Benchmark
  def semanticDiff(state: UnrelatedChangeState, blackhole: Blackhole): Unit =
    blackhole.consume(TreeDiffer.diff(state.previous.tree, state.currentTree))

  @Benchmark
  def phoenixEncoding(
    state: UnrelatedChangeState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    val encoded = PhoenixRenderedEncoder
      .update(state.phoenixState, state.delta)
      .fold(error => throw RuntimeException(error.toString), identity)
    counters.outputBytes += encoded._2.toJson.length.toLong
    blackhole.consume(encoded)
end UnrelatedChangeBenchmark
