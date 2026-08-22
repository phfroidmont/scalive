package scalive.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import zio.json.*
import zio.{Task, ZIO}

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*
import scalive.streams.*

object StreamAndLifecycleBenchmark:
  final case class Row(id: Int, value: String)

  object RetainedComponent extends LiveComponent.Eventless[Row, Row]:
    def mount(props: Row, ctx: MountContext): Task[Row] = ZIO.succeed(props)
    def view(props: Signal[Row], model: Signal[Row], self: ComponentRef[Nothing]) = div(
      props.map(_.value)
    )

  def stream(
    identity: LiveStreamIdentity,
    rows: Vector[Row],
    generation: Long,
    patch: Option[Row]
  ) =
    val entries = rows.map(row => LiveStreamEntry(s"events-${row.id}", row))
    val inserts = patch.toVector.map(row =>
      LiveStreamInsert(
        LiveStreamEntry(s"events-${row.id}", row),
        StreamAt.Last,
        None,
        updateOnly = false
      )
    )
    LiveStream(identity, "events", generation, entries, inserts, Vector.empty, reset = false)

@State(Scope.Benchmark)
class StreamAndLifecycleState:
  import BenchmarkSupport.*
  import StreamAndLifecycleBenchmark.*

  @Param(Array("10000"))
  var rowCount: Int = 0

  var streamProgram: RenderProgram[LiveStream[Row], Nothing]    = null
  var streamPrevious: CommittedRender[Nothing]                  = null
  var streamDelta: RenderDelta                                  = null
  var phoenixState: PhoenixRenderedState                        = null
  var failureProgram: RenderProgram[Vector[Row], Nothing]       = null
  var failurePrevious: CommittedRender[Nothing]                 = null
  var invalidRows: Vector[Row]                                  = Vector.empty
  var nestedPrevious: EvaluatedTree                             = null
  var nestedCurrent: EvaluatedTree                              = null
  var componentRootProgram: RenderProgram[Vector[Row], Nothing] = null
  var componentRootPrevious: CommittedRender[Nothing]           = null
  var componentPrograms: Vector[RenderProgram[Row, Nothing]]    = Vector.empty
  var componentPrevious: Vector[CommittedRender[Nothing]]       = Vector.empty

  @Setup(Level.Trial)
  def setup(): Unit =
    val rows           = Vector.tabulate(rowCount)(i => Row(i, s"value-$i"))
    val streamIdentity = LiveStreamIdentity.fresh()
    streamProgram = RenderProgram
      .compile[LiveStream[Row], Nothing](model =>
        model.renderIn(div)(row => div(span(row.map(_.value))))
      ).fold(throw _, identity)
    streamPrevious = run(streamProgram.evaluate(stream(streamIdentity, rows, 1L, None))).commit
    val inserted = Row(rowCount, "small-patch")
    val current  = run(
      streamProgram.evaluate(
        stream(streamIdentity, rows :+ inserted, 2L, Some(inserted)),
        Some(streamPrevious)
      )
    )
    streamDelta = TreeDiffer.diff(streamPrevious.tree, current.tree)
    phoenixState = PhoenixRenderedEncoder
      .initial(streamPrevious.tree)
      .fold(error => throw RuntimeException(error.toString), _._1)
    require(changeCount(streamDelta) == 1)
    run(current.discard)

    failureProgram = RenderProgram
      .compile[Vector[Row], Nothing](model =>
        div(model.splitBy(_.id)((_, row) => div(row.map(_.value))))
      ).fold(throw _, identity)
    failurePrevious = run(failureProgram.evaluate(rows.take(1000))).commit
    invalidRows = rows.take(999) :+ Row(500, "duplicate-at-end")
    require(run(failureProgram.evaluate(invalidRows, Some(failurePrevious)).either).isLeft)
    val afterFailure = run(failureProgram.evaluate(rows.take(1000), Some(failurePrevious)))
    require(TreeDiffer.diff(failurePrevious.tree, afterFailure.tree) == RenderDelta.Empty)
    run(afterFailure.discard)

    componentRootProgram = RenderProgram
      .compile[Vector[Row], Nothing](model =>
        div(
          (0 until 32).map(index =>
            liveComponent(RetainedComponent, s"component-$index", model.map(_(index)))
          )
        )
      ).fold(throw _, identity)
    componentPrograms = Vector.fill(32)(
      RenderProgram
        .compile[Row, Nothing](row =>
          div((0 until 8).map(index => span(row.map(value => s"${value.value}-$index"))))
        ).fold(throw _, identity)
    )
    val tokens       = Vector.fill(32)(Object())
    val rootInitial  = run(componentRootProgram.evaluate(rows))
    val childInitial = componentPrograms.zip(rows).map((program, row) => run(program.evaluate(row)))
    val initialResolutions =
      rootInitial.componentRequirements.zipWithIndex.map { (requirement, index) =>
        requirement.resolve(ComponentRef.runtime(tokens(index)), tokens(index), childInitial(index))
      }
    val rootResolved = rootInitial.resolveComponents(initialResolutions).fold(throw _, identity)
    componentPrevious = childInitial.map(_.commit)
    componentRootPrevious = rootResolved.commit

    val changedRows  = rows.updated(16, rows(16).copy(value = "nested-change"))
    val rootCurrent  = run(componentRootProgram.evaluate(changedRows, Some(componentRootPrevious)))
    val childCurrent = componentPrograms.zip(changedRows).zip(componentPrevious).map {
      case ((program, row), previous) => run(program.evaluate(row, Some(previous)))
    }
    val currentResolutions =
      rootCurrent.componentRequirements.zipWithIndex.map { (requirement, index) =>
        requirement.resolve(ComponentRef.runtime(tokens(index)), tokens(index), childCurrent(index))
      }
    val currentResolved = rootCurrent.resolveComponents(currentResolutions).fold(throw _, identity)
    nestedPrevious = componentRootPrevious.tree
    nestedCurrent = currentResolved.tree
    require(changeCount(TreeDiffer.diff(nestedPrevious, nestedCurrent)) == 9)
    run(currentResolved.discard *> ZIO.foreachDiscard(childCurrent)(_.discard))
  end setup

  @TearDown(Level.Trial)
  def close(): Unit = run(
    streamPrevious.close *> streamProgram.close *> failurePrevious.close *> failureProgram.close *>
      componentRootPrevious.close *> componentRootProgram.close *>
      ZIO.foreachDiscard(componentPrevious)(_.close) *>
      ZIO.foreachDiscard(componentPrograms)(_.close)
  )
end StreamAndLifecycleState

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class StreamAndLifecycleBenchmark:
  import BenchmarkSupport.*

  @Benchmark
  def largeStreamSmallPatch(
    state: StreamAndLifecycleState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    val encoded = PhoenixRenderedEncoder
      .update(state.phoenixState, state.streamDelta)
      .fold(error => throw RuntimeException(error.toString), identity)
    counters.outputBytes += encoded._2.toJson.length.toLong
    counters.diffChanges += changeCount(state.streamDelta)
    counters.retainedTemplates += state.rowCount.toLong
    counters.retainedHeapBytes += state.rowCount.toLong * 64L
    blackhole.consume(encoded)

  @Benchmark
  def nestedRetainedTemplates(
    state: StreamAndLifecycleState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    val delta = TreeDiffer.diff(state.nestedPrevious, state.nestedCurrent)
    counters.diffChanges += changeCount(delta)
    counters.retainedTemplates += 32L * 8L
    counters.retainedHeapBytes += 32L * 8L * 64L
    counters.lifecycleResources += 32L
    blackhole.consume(delta)

  @Benchmark
  def lateCandidateFailureRollback(
    state: StreamAndLifecycleState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    val failed = run(
      state.failureProgram.evaluate(state.invalidRows, Some(state.failurePrevious)).either
    )
    counters.retainedTemplates += 1000L
    counters.retainedHeapBytes += 1000L * 64L
    counters.lifecycleResources += 1000L
    blackhole.consume(failed)
end StreamAndLifecycleBenchmark
