package scalive.benchmarks

import org.openjdk.jmh.annotations.{AuxCounters, Scope, Setup, State}
import zio.{Runtime, Unsafe, ZIO}

import scalive.render.{RenderChange, RenderDelta}

private[benchmarks] object BenchmarkSupport:
  def run[A](effect: ZIO[Any, ?, A]): A =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  def changeCount(delta: RenderDelta): Int = delta match
    case RenderDelta.Empty              => 0
    case RenderDelta.Replace(_)         => 1
    case RenderDelta.Update(_, changes) =>
      changes.map {
        case RenderChange.Component(_, nested) => 1 + changeCount(nested)
        case _                                 => 1
      }.sum

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
class BenchmarkCounters:
  var outputBytes: Long        = 0L
  var diffChanges: Long        = 0L
  var signalSamples: Long      = 0L
  var renderNanos: Long        = 0L
  var diffNanos: Long          = 0L
  var retainedTemplates: Long  = 0L
  var retainedHeapBytes: Long  = 0L
  var lifecycleResources: Long = 0L

  @Setup
  def clear(): Unit =
    outputBytes = 0L
    diffChanges = 0L
    signalSamples = 0L
    renderNanos = 0L
    diffNanos = 0L
    retainedTemplates = 0L
    retainedHeapBytes = 0L
    lifecycleResources = 0L
