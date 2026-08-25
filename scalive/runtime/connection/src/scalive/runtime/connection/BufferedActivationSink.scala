package scalive.runtime.connection

import zio.*

final private[scalive] class BufferedActivationSink[A] private (
  capacity: Int,
  destination: A => Task[Unit],
  overflow: Int => Throwable,
  gate: Semaphore):
  private var values: Vector[A] = Vector.empty
  private var active: Boolean   = false

  def offer(value: A): Task[Unit] = gate.withPermit {
    if active then destination(value)
    else if values.size >= capacity then ZIO.fail(overflow(capacity))
    else
      values = values :+ value
      ZIO.unit
  }

  def activate: Task[Unit] = gate.withPermit {
    ZIO.foreachDiscard(values)(destination) *> ZIO.succeed {
      values = Vector.empty
      active = true
    }
  }

private[scalive] object BufferedActivationSink:
  def make[A](
    capacity: Int,
    destination: A => Task[Unit],
    overflow: Int => Throwable
  ): UIO[BufferedActivationSink[A]] =
    Semaphore.make(1L).map(new BufferedActivationSink(capacity, destination, overflow, _))
