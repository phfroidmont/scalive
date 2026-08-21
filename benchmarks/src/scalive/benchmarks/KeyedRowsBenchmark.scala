package scalive.benchmarks

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import zio.json.*

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*

object KeyedRowsBenchmark:
  final case class Row(id: Int, label: String, group: Int)

@State(Scope.Benchmark)
class KeyedRowsState:
  import BenchmarkSupport.*
  import KeyedRowsBenchmark.*

  @Param(Array("100", "1000", "10000"))
  var rowCount: Int = 0

  @Param(Array("update", "reorder", "removal", "reintroduction"))
  var operation: String = ""

  var program: RenderProgram[Vector[Row], String] = null
  var previous: CommittedRender[String]           = null
  var currentInput: Vector[Row]                   = Vector.empty
  var currentTree: EvaluatedTree                  = null
  var delta: RenderDelta                          = null
  var phoenixState: PhoenixRenderedState          = null
  val timedSamples                                = AtomicLong(0L)

  private def rows = Vector.tabulate(rowCount)(i => Row(i, s"row-$i", i % 17))

  private def view(model: Signal[Vector[Row]]): HtmlElement[String] =
    table(
      tbody(
        model.splitBy(_.id) { (id, row) =>
          val label = row.map { value =>
            timedSamples.incrementAndGet()
            value.label
          }
          tr(
            dataAttr("row-id") := id.toString,
            td(label),
            td(row.map(_.group.toString)),
            td(button(on.click(s"select-$id"), "select"))
          )
        }
      )
    )

  @Setup(Level.Trial)
  def setup(): Unit =
    val all = rows
    program = RenderProgram.compile[Vector[Row], String](view).fold(throw _, identity)
    val initial = run(program.evaluate(all)).commit
    operation match
      case "update" =>
        previous = initial
        val at = rowCount / 2
        currentInput = all.updated(at, all(at).copy(label = "changed"))
      case "reorder" =>
        previous = initial
        currentInput = all.tail :+ all.head
      case "removal" =>
        previous = initial
        currentInput = all.patch(rowCount / 2, Nil, 1)
      case "reintroduction" =>
        val removed = all.patch(rowCount / 2, Nil, 1)
        val removal = run(program.evaluate(removed, Some(initial))).commit
        run(initial.close)
        previous = removal
        currentInput = all
      case other => throw IllegalArgumentException(other)

    val candidate = run(program.evaluate(currentInput, Some(previous)))
    currentTree = candidate.tree
    delta = TreeDiffer.diff(previous.tree, currentTree)
    phoenixState = PhoenixRenderedEncoder
      .initial(previous.tree)
      .fold(error => throw RuntimeException(error.toString), _._1)
    run(candidate.discard)

    require(
      rowCount == previous.tree.root
        .asInstanceOf[EvaluatedNode.Element].children.head
        .asInstanceOf[EvaluatedNode.Element].children.head.asInstanceOf[
          EvaluatedNode.Keyed
        ].rows.size ||
        operation == "reintroduction"
    )
    require(operation != "update" || changeCount(delta) == 1)
    require(operation == "update" || changeCount(delta) >= 1)
  end setup

  @TearDown(Level.Trial)
  def close(): Unit =
    run(previous.close *> program.close)
end KeyedRowsState

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class KeyedRowsBenchmark:
  import BenchmarkSupport.*

  @Benchmark
  def semanticDiff(state: KeyedRowsState, counters: BenchmarkCounters, blackhole: Blackhole): Unit =
    val started = System.nanoTime()
    val result  = TreeDiffer.diff(state.previous.tree, state.currentTree)
    counters.diffNanos += System.nanoTime() - started
    counters.diffChanges += changeCount(result)
    counters.retainedTemplates += state.rowCount.toLong
    counters.retainedHeapBytes += state.rowCount.toLong * 64L
    blackhole.consume(result)

  @Benchmark
  def phoenixEncoding(state: KeyedRowsState, counters: BenchmarkCounters, blackhole: Blackhole)
    : Unit =
    val encoded = PhoenixRenderedEncoder
      .update(state.phoenixState, state.delta)
      .fold(error => throw RuntimeException(error.toString), identity)
    val json = encoded._2.toJson
    counters.outputBytes += json.length.toLong
    counters.diffChanges += changeCount(state.delta)
    blackhole.consume(encoded)

  @Benchmark
  def renderDiffAndEncode(
    state: KeyedRowsState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    state.timedSamples.set(0L)
    val renderStarted = System.nanoTime()
    val result        = run {
      state.program.evaluate(state.currentInput, Some(state.previous)).flatMap { candidate =>
        val renderFinished = System.nanoTime()
        val diffStarted    = System.nanoTime()
        val delta          = TreeDiffer.diff(state.previous.tree, candidate.tree)
        val diffFinished   = System.nanoTime()
        val encoded        = PhoenixRenderedEncoder
          .update(state.phoenixState, delta)
          .fold(error => throw RuntimeException(error.toString), identity)
        candidate.discard.as(
          (encoded, delta, renderFinished - renderStarted, diffFinished - diffStarted)
        )
      }
    }
    counters.renderNanos += result._3
    counters.diffNanos += result._4
    counters.diffChanges += changeCount(result._2)
    counters.outputBytes += result._1._2.toJson.length.toLong
    counters.signalSamples += state.timedSamples.get()
    counters.retainedTemplates += state.rowCount.toLong
    counters.retainedHeapBytes += state.rowCount.toLong * 64L
    blackhole.consume(result._1)
  end renderDiffAndEncode
end KeyedRowsBenchmark
