package scalive.benchmarks

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import scalive.*
import scalive.render.*

object BindingsAndSignalsBenchmark:
  final case class Model(version: Long, displayed: String)

@State(Scope.Benchmark)
class BindingsAndSignalsState:
  import BenchmarkSupport.*
  import BindingsAndSignalsBenchmark.*

  @Param(Array("100", "1000"))
  var rowCount: Int = 0

  var bindingProgram: RenderProgram[Int, String]  = null
  var bindingRender: CommittedRender[String]      = null
  var bindingIds: Vector[BindingId]               = Vector.empty
  var equalProgram: RenderProgram[Model, Nothing] = null
  var equalPrevious: CommittedRender[Nothing]     = null
  val sampleCounter                               = AtomicLong(0L)

  @Setup(Level.Trial)
  def setup(): Unit =
    bindingProgram = RenderProgram
      .compile[Int, String](_ =>
        div((0 until rowCount).map { id =>
          div(
            idAttr := s"binding-$id",
            on.click(s"click-$id"),
            on.blur(s"blur-$id"),
            on.focus(s"focus-$id"),
            on.keyDown(payload => s"down-$id-${payload.getOrElse("key", "")}"),
            on.keyUp(payload => s"up-$id-${payload.getOrElse("key", "")}"),
            "row"
          )
        })
      ).fold(throw _, identity)
    bindingRender = run(bindingProgram.evaluate(rowCount)).commit
    bindingIds = bindingRender.bindings.ids
    require(bindingIds.size == rowCount * 5)

    equalProgram = RenderProgram
      .compile[Model, Nothing] { model =>
        val text = model.map { value =>
          sampleCounter.incrementAndGet()
          value.displayed
        }
        val css = model.map { value =>
          sampleCounter.incrementAndGet()
          if value.displayed.nonEmpty then "present" else "empty"
        }
        div(cls := css, span(text), span(text))
      }.fold(throw _, identity)
    equalPrevious = run(equalProgram.evaluate(Model(1L, "same"))).commit
    sampleCounter.set(0L)
    val equalCandidate = run(equalProgram.evaluate(Model(2L, "same"), Some(equalPrevious)))
    require(TreeDiffer.diff(equalPrevious.tree, equalCandidate.tree) == RenderDelta.Empty)
    require(sampleCounter.get() == 2L)
    run(equalCandidate.discard)
  end setup

  @TearDown(Level.Trial)
  def close(): Unit = run(
    bindingRender.close *> bindingProgram.close *> equalPrevious.close *> equalProgram.close
  )
end BindingsAndSignalsState

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class BindingsAndSignalsBenchmark:
  import BenchmarkSupport.*
  import BindingsAndSignalsBenchmark.*

  @Benchmark
  def bindingLookup(
    state: BindingsAndSignalsState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    var index = 0
    var found = 0
    while index < state.bindingIds.length do
      if state.bindingRender.bindings.resolve(state.bindingIds(index)).nonEmpty then found += 1
      index += 1
    counters.lifecycleResources += state.bindingIds.length.toLong
    blackhole.consume(found)

  @Benchmark
  def changedInputEqualRenderedValues(
    state: BindingsAndSignalsState,
    counters: BenchmarkCounters,
    blackhole: Blackhole
  ): Unit =
    state.sampleCounter.set(0L)
    val started   = System.nanoTime()
    val candidate = run(state.equalProgram.evaluate(Model(2L, "same"), Some(state.equalPrevious)))
    val rendered  = System.nanoTime()
    val delta     = TreeDiffer.diff(state.equalPrevious.tree, candidate.tree)
    counters.renderNanos += rendered - started
    counters.diffNanos += System.nanoTime() - rendered
    counters.signalSamples += state.sampleCounter.get()
    counters.diffChanges += changeCount(delta)
    run(candidate.discard)
    blackhole.consume(delta)
end BindingsAndSignalsBenchmark
