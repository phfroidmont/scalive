package scaliveapi

import zio.*
import zio.test.*

import scalive.*

object LifecycleObserverSpec extends ZIOSpecDefault:
  override def spec = suite("LifecycleObserverSpec")(
    test("join counters classify reconnects and retries without becoming policy") {
      val initial   = LifecycleJoinAttempt(Some(0L), Some(0L))
      val reconnect = LifecycleJoinAttempt(Some(2L), Some(0L))
      val retry     = LifecycleJoinAttempt(Some(0L), Some(1L))

      assertTrue(
        !initial.isReconnect,
        !initial.isRetry,
        reconnect.isReconnect,
        !reconnect.isRetry,
        !retry.isReconnect,
        retry.isRetry
      )
    },
    test("composed observers isolate defects and preserve order") {
      for
        events <- Ref.make(Vector.empty[String])
        broken  = LifecycleObserver.fromFunction(_ => ZIO.dieMessage("observer defect"))
        working = LifecycleObserver.fromFunction(event => events.update(_ :+ event.name))
        event   = LifecycleEvent.DisconnectedRenderSucceeded(1L, 2L)
        _      <- broken.andThen(working).observe(event)
        result <- events.get
      yield assertTrue(result == Vector("disconnected_render_succeeded"))
    },
    test("runtime-safe observation suspends synchronous exceptions") {
      for
        observed <- Ref.make(false)
        broken = LifecycleObserver.fromFunction(_ => throw Exception("synchronous defect"))
        working = LifecycleObserver.fromFunction(_ => observed.set(true))
        result <- broken
                    .andThen(working).observe(
                      LifecycleEvent.DisconnectedRenderSucceeded(1L, 2L)
                    ).exit
        continued <- observed.get
      yield assertTrue(result.isSuccess, continued)
    },
    test("events expose correlations without application payload fields") {
      val lifecycle = ConnectedLifecycleContext(1L, 2L, 3L)
      val event = LifecycleEvent.TurnFailed(
        lifecycle,
        turnId = 4L,
        commandId = Some(5L),
        LifecycleTurnKind.BrowserEvent,
        durationNanos = 6L,
        LifecycleError(LifecycleFailure.Stage(LifecycleFailureStage.Handler))
      )
      val correlated = event match
        case value: LifecycleEvent.TurnFailed =>
          value.lifecycle == lifecycle && value.turnId == 4L
        case _ => false

      assertTrue(
        correlated,
        !event.toString.contains("Json"),
        !event.toString.contains("session")
      )
    }
  )
end LifecycleObserverSpec
