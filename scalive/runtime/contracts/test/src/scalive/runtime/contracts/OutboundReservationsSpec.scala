package scalive.runtime.contracts

import zio.Duration
import zio.ZIO
import zio.test.*

object OutboundReservationsSpec extends ZIOSpecDefault:
  override def spec = suite("OutboundReservationsSpec")(
    test("rejects non-positive capacity") {
      for result <- InMemoryOutboundReservations.make[String](0).either
      yield assertTrue(result == Left(OutboundReservationError.InvalidCapacity(0)))
    },
    test("reports saturation without dropping a reservation") {
      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        _            <- reservations.reserve
        saturated    <- reservations.reserve.either
      yield assertTrue(saturated == Left(OutboundReservationError.Saturated(1)))
    },
    test("takes batches in reservation order when publication is out of order") {
      val firstBatch  = OutboundBatch.from(List("first-1", "first-2"))
      val secondBatch = OutboundBatch.single("second")

      for
        reservations <- InMemoryOutboundReservations.make[String](2)
        first         <- reservations.reserve
        second        <- reservations.reserve
        _             <- second.publish(secondBatch)
        waiting       <- reservations.take.fork
        beforeFirst   <- waiting.poll
        _             <- first.publish(firstBatch)
        observedFirst <- waiting.join
        observedSecond <- reservations.take
      yield assertTrue(
        beforeFirst.isEmpty,
        observedFirst == firstBatch,
        observedSecond == secondBatch,
        observedFirst.items == Vector("first-1", "first-2")
      )
    },
    test("cancellation unblocks a later published reservation") {
      val batch = OutboundBatch.single("later")

      for
        reservations <- InMemoryOutboundReservations.make[String](2)
        first         <- reservations.reserve
        second        <- reservations.reserve
        _             <- second.publish(batch)
        waiting       <- reservations.take.fork
        _             <- first.cancel
        observed      <- waiting.join
      yield assertTrue(observed == batch)
    },
    test("publish and release are idempotent and first completion wins") {
      val firstBatch = OutboundBatch.single("first")

      for
        reservations <- InMemoryOutboundReservations.make[String](2)
        published     <- reservations.reserve
        released      <- reservations.reserve
        _             <- published.publish(firstBatch).repeatN(2)
        _             <- published.release
        _             <- released.release.repeatN(2)
        _             <- released.publish(OutboundBatch.single("ignored"))
        observed      <- reservations.take
      yield assertTrue(observed == firstBatch)
    },
    test("publish completes without waiting for a consumer") {
      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        reservation  <- reservations.reserve
        completed <- reservation
          .publish(OutboundBatch.single("ready"))
          .timeout(Duration.fromSeconds(1L))
      yield assertTrue(completed.contains(()))
    },
    test("an interrupted take retains its in-flight reservation") {
      val expected = OutboundBatch.single("retained")
      val later    = OutboundBatch.single("later")

      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        first         <- reservations.reserve
        interrupted   <- reservations.take.fork
        second <- {
          def reserveWhenAvailable: ZIO[Any, OutboundReservationError, OutboundReservation[String]] =
            reservations.reserve.catchSome {
              case OutboundReservationError.Saturated(_) => ZIO.yieldNow *> reserveWhenAvailable
            }

          reserveWhenAvailable
        }
        _        <- interrupted.interrupt
        _        <- first.publish(expected)
        _        <- second.publish(later)
        observed <- reservations.take
      yield assertTrue(observed == expected)
    },
    test("shutdown interrupts a pending take") {
      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        waiting       <- reservations.take.fork
        _             <- ZIO.yieldNow
        before        <- waiting.poll
        _             <- reservations.shutdown
        result        <- waiting.join.either
      yield assertTrue(
        before.isEmpty,
        result == Left(OutboundReservationError.Shutdown)
      )
    },
    test("shutdown leaves an outstanding reservation safe to publish") {
      val batch = OutboundBatch.single("after-shutdown")

      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        reservation  <- reservations.reserve
        _             <- reservations.shutdown
        published     <- reservation.publish(batch).timeout(Duration.fromSeconds(1L))
        result        <- reservations.take.either
      yield assertTrue(
        published.contains(()),
        result == Left(OutboundReservationError.Shutdown)
      )
    },
    test("reports shutdown explicitly") {
      for
        reservations <- InMemoryOutboundReservations.make[String](1)
        _             <- reservations.shutdown
        result        <- reservations.reserve.either
      yield assertTrue(result == Left(OutboundReservationError.Shutdown))
    }
  )
