package scalive.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*
import scalive.runtime.connection.StreamStore
import scalive.streams.*

object StreamTurnBenchmark:
  final case class Row(id: Int, label: String)
  final case class Model(counter: Int, stream: LiveStream[Row])

  val Rows: LiveStreamDef[Row, Int] = LiveStreamDef.byId[Row, Int]("rows")(_.id)

@State(Scope.Benchmark)
class StreamTurnState:
  import BenchmarkSupport.*
  import StreamTurnBenchmark.*

  @Param(Array("1000", "10000"))
  var rowCount: Int = 0

  // `unrelated` changes a counter next to the stream; `insert` appends one row to it, and `delete`
  // deletes the one in its middle.
  @Param(Array("unrelated", "insert", "delete"))
  var operation: String = ""

  var rows: Vector[Row]                     = Vector.empty
  var program: RenderProgram[Model, String] = null
  var previous: CommittedRender[String]     = null
  var next: Model                           = null
  var currentTree: EvaluatedTree            = null
  var delta: RenderDelta                    = null
  var phoenixState: PhoenixRenderedState    = null

  private def view(model: Signal[Model]): HtmlElement[String] =
    div(
      span(model.map(_.counter.toString)),
      model
        .map(_.stream).renderIn(ul)(row =>
          li(row.map(_.label), button(on.click("select"), "select"))
        )
    )

  @Setup(Level.Trial)
  def setup(): Unit =
    rows = Vector.tabulate(rowCount)(i => Row(i, s"row-$i"))
    val created = StreamStore.empty.create(Rows, rows)
    val settled = created.store.settle
    val initial = Model(0, created.stream)
    program = RenderProgram.compile[Model, String](view).fold(throw _, identity)
    previous = run(program.evaluate(initial)).commit
    next = operation match
      case "unrelated" => initial.copy(counter = 1)
      case "insert"    => initial.copy(stream = settled.insert(Rows, Row(rowCount, "new")).stream)
      case "delete"    => initial.copy(stream = settled.delete(Rows, rowCount / 2).stream)
      case other       => throw IllegalArgumentException(other)
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
end StreamTurnState

/** One turn on a page holding a large stream: a change next to it, or one row inserted into it or
  * deleted from it.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class StreamTurnBenchmark:
  import BenchmarkSupport.*
  import StreamTurnBenchmark.*

  @Benchmark
  def evaluate(state: StreamTurnState, blackhole: Blackhole): Unit =
    val tree = run(
      state.program
        .evaluate(state.next, Some(state.previous)).flatMap(candidate =>
          candidate.discard.as(candidate.tree)
        )
    )
    blackhole.consume(tree)

  @Benchmark
  def semanticDiff(state: StreamTurnState, blackhole: Blackhole): Unit =
    blackhole.consume(TreeDiffer.diff(state.previous.tree, state.currentTree))

  @Benchmark
  def phoenixEncoding(state: StreamTurnState, blackhole: Blackhole): Unit =
    blackhole.consume(
      PhoenixRenderedEncoder
        .update(state.phoenixState, state.delta)
        .fold(error => throw RuntimeException(error.toString), identity)
    )

  /** Creates a stream of all the rows, as a mount does. */
  @Benchmark
  def createStream(state: StreamTurnState, blackhole: Blackhole): Unit =
    blackhole.consume(StreamStore.empty.create(Rows, state.rows))
end StreamTurnBenchmark
