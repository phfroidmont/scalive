package scalive

final class MutableValue[T] private (private var value: T):
  def currentValue: T         = value
  def set(nextValue: T): Unit =
    value = nextValue

object MutableValue:
  def apply[T](initialValue: T): MutableValue[T] =
    new MutableValue(initialValue)
