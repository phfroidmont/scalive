package scalive.runtime.contracts

/** Protocol-neutral outbound values that must retain their original order. */
final case class OutboundBatch[+A](items: Vector[A])

object OutboundBatch:
  def from[A](items: IterableOnce[A]): OutboundBatch[A] = OutboundBatch(Vector.from(items))

  def single[A](item: A): OutboundBatch[A] = OutboundBatch(Vector(item))
