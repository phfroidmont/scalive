package scalive

/** Names a value that a LiveView passes to its layouts, and the type of that value.
  *
  * A layout declares the keys it reads, usually in its companion object, and reads each with
  * [[LiveLayoutContext.layoutValue]]. A LiveView provides values for them from its model with
  * [[LiveView.layoutValues]], as in `LiveLayoutValues(AppLayout.Heading := "Project scalive")`.
  *
  * Keys are compared by identity, not by name, so keys declared by different layouts never clash.
  * The key is invariant in `A`, so a value of another type cannot be provided for it.
  *
  * @param name
  *   A label for the key in test output. It does not identify the key.
  * @tparam A
  *   the type of the value
  */
final class LiveLayoutKey[A](val name: String):
  /** Pairs this key with the value a LiveView provides for it. */
  def :=(value: A): LiveLayoutValues.Entry = new LiveLayoutValues.Entry(this, value)

  override def toString: String = s"LiveLayoutKey($name)"

/** The values a LiveView passes to its layouts for one model, each under its [[LiveLayoutKey]].
  *
  * Build it with `LiveLayoutValues(key := value, ...)` in [[LiveView.layoutValues]]. When an entry
  * repeats a key, the later one is kept.
  */
final class LiveLayoutValues private (private val entries: Map[LiveLayoutKey[?], Any]):
  /** Returns the value provided for `key`, or `None` when there is none. */
  def get[A](key: LiveLayoutKey[A]): Option[A] = entries.get(key).map(_.asInstanceOf[A])

  override def equals(other: Any): Boolean = other match
    case values: LiveLayoutValues => entries == values.entries
    case _                        => false

  override def hashCode: Int = entries.hashCode

  override def toString: String =
    entries
      .map((key, value) => s"${key.name} -> $value")
      .mkString("LiveLayoutValues(", ", ", ")")

/** Builds [[LiveLayoutValues]]. */
object LiveLayoutValues:
  /** No values, the default of [[LiveView.layoutValues]]. */
  val empty: LiveLayoutValues = new LiveLayoutValues(Map.empty)

  /** Collects `entries`, built with `key := value`. A later entry for the same key wins. */
  def apply(entries: Entry*): LiveLayoutValues =
    new LiveLayoutValues(entries.map(entry => entry.key -> entry.value).toMap)

  /** Permits comparing values under strict equality, for example in tests of a page's values. */
  given CanEqual[LiveLayoutValues, LiveLayoutValues] = CanEqual.derived

  /** One value paired with its key, built with `key := value`. */
  final class Entry private[scalive] (
    private[LiveLayoutValues] val key: LiveLayoutKey[?],
    private[LiveLayoutValues] val value: Any):
    override def toString: String = s"${key.name} := $value"
