package scalive.runtime.contracts

import zio.Promise
import zio.Queue
import zio.Ref
import zio.Semaphore
import zio.UIO
import zio.ZIO

enum OutboundReservationError:
  case InvalidCapacity(capacity: Int)
  case Saturated(capacity: Int)
  case Shutdown

trait OutboundReservation[A]:
  /** Publishes into the queue slot installed by `reserve`. The first publish or release wins. */
  def publish(batch: OutboundBatch[A]): UIO[Unit]

  /** Leaves a tombstone in this slot so consumers can advance. */
  def release: UIO[Unit]

  final def cancel: UIO[Unit] = release

trait OutboundReservations[A]:
  /** Installs one bounded slot without waiting for a consumer.
    *
    * Callers mask the handoff from this effect to reservation ownership, so implementations must
    * keep acquisition bounded and express saturation as [[OutboundReservationError.Saturated]].
    */
  def reserve: ZIO[Any, OutboundReservationError, OutboundReservation[A]]

  /** Takes the next published batch, skipping released reservations. */
  def take: ZIO[Any, OutboundReservationError, OutboundBatch[A]]

  def shutdown: UIO[Unit]

final class InMemoryOutboundReservations[A] private (
  val capacity: Int,
  slots: Queue[Option[Promise[Nothing, Option[OutboundBatch[A]]]]],
  inFlight: Ref[Option[Promise[Nothing, Option[OutboundBatch[A]]]]],
  stopped: Promise[Nothing, Unit],
  gate: Semaphore,
  consumerGate: Semaphore)
    extends OutboundReservations[A]:
  override def reserve: ZIO[Any, OutboundReservationError, OutboundReservation[A]] =
    ZIO.uninterruptible(gate.withPermit {
      stopped.isDone.flatMap {
        case true  => ZIO.fail(OutboundReservationError.Shutdown)
        case false =>
          for
            slot        <- Promise.make[Nothing, Option[OutboundBatch[A]]]
            accepted    <- slots.offer(Some(slot))
            reservation <-
              if accepted then ZIO.succeed(PromiseOutboundReservation(slot))
              else ZIO.fail(OutboundReservationError.Saturated(capacity))
          yield reservation
      }
    })

  override def take: ZIO[Any, OutboundReservationError, OutboundBatch[A]] =
    consumerGate.withPermit {
      def failOnShutdown[B](effect: ZIO[Any, OutboundReservationError, B]) =
        stopped.isDone.flatMap {
          case true  => ZIO.fail(OutboundReservationError.Shutdown)
          case false =>
            effect.raceFirst(stopped.await *> ZIO.fail(OutboundReservationError.Shutdown))
        }

      ZIO.uninterruptibleMask { restore =>
        def takeNext: ZIO[Any, OutboundReservationError, OutboundBatch[A]] =
          val slot = inFlight.get.flatMap {
            case Some(slot) => ZIO.succeed(slot)
            case None       =>
              stopped.isDone.flatMap {
                case true  => ZIO.fail(OutboundReservationError.Shutdown)
                case false =>
                  restore(slots.take).flatMap {
                    case Some(slot) => inFlight.set(Some(slot)).as(slot)
                    case None       => ZIO.fail(OutboundReservationError.Shutdown)
                  }
              }
          }

          slot.flatMap { slot =>
            restore(failOnShutdown(slot.await)).flatMap {
              case Some(batch) => inFlight.set(None).as(batch)
              case None        => inFlight.set(None) *> takeNext
            }
          }

        takeNext
      }
    }

  override def shutdown: UIO[Unit] =
    gate.withPermit(stopped.succeed(()) *> slots.offer(None).unit)
end InMemoryOutboundReservations

final private class PromiseOutboundReservation[A](
  slot: Promise[Nothing, Option[OutboundBatch[A]]])
    extends OutboundReservation[A]:
  override def publish(batch: OutboundBatch[A]): UIO[Unit] = slot.succeed(Some(batch)).unit

  override def release: UIO[Unit] = slot.succeed(None).unit

object InMemoryOutboundReservations:
  def make[A](
    capacity: Int
  ): ZIO[Any, OutboundReservationError, InMemoryOutboundReservations[A]] =
    if capacity <= 0 then ZIO.fail(OutboundReservationError.InvalidCapacity(capacity))
    else
      for
        slots        <- Queue.dropping[Option[Promise[Nothing, Option[OutboundBatch[A]]]]](capacity)
        inFlight     <- Ref.make(Option.empty[Promise[Nothing, Option[OutboundBatch[A]]]])
        stopped      <- Promise.make[Nothing, Unit]
        gate         <- Semaphore.make(1L)
        consumerGate <- Semaphore.make(1L)
      yield InMemoryOutboundReservations(capacity, slots, inFlight, stopped, gate, consumerGate)
