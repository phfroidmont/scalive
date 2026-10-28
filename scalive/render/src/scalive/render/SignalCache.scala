package scalive.render

import java.util.IdentityHashMap

import scalive.Signal
import scalive.render.SignalEvaluation.PackedSignalValue

/** The samples of the signals placed in one program or row, by signal identity.
  *
  * Every row keeps one, so it holds no object per sample, nor one around them: a few samples, as a
  * row usually has, sit in one array as signal, sample, signal, sample… and are found by scanning
  * it; more go in an identity map that is never changed once built.
  */
private[render] opaque type SignalCache =
  Array[AnyRef] | IdentityHashMap[Signal[?], SignalSample[?]]

private[render] object SignalCache:
  val empty: SignalCache = Array.empty[AnyRef]

  // Above this many samples, a scan costs more than hashing.
  private val fewSamples = 8

  def of(samples: collection.Seq[PackedSignalValue[SignalSample]]): SignalCache =
    if samples.isEmpty then empty
    else if samples.length <= fewSamples then
      val entries = new Array[AnyRef](samples.length * 2)
      var index   = 0
      while index < samples.length do
        entries(index * 2) = samples(index).signal
        entries(index * 2 + 1) = samples(index).value
        index += 1
      entries
    else
      val entries = IdentityHashMap[Signal[?], SignalSample[?]](samples.length)
      samples.foreach(sample => entries.put(sample.signal, sample.value))
      entries

  extension (cache: SignalCache)
    def get[A](signal: Signal[A]): Option[SignalSample[A]] = cache match
      case entries: Array[AnyRef] =>
        var index = entries.length - 2
        while index >= 0 && (entries(index) ne signal) do index -= 2
        if index >= 0 then Some(entries(index + 1).asInstanceOf[SignalSample[A]])
        else None
      case entries: IdentityHashMap[Signal[?], SignalSample[?]] @unchecked =>
        Option(entries.get(signal).asInstanceOf[SignalSample[A]])
end SignalCache
