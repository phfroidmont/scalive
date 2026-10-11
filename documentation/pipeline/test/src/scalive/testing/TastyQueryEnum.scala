package scalive.testing

/** An enum whose cases exercise enum extraction. */
enum TastyQueryEnum derives CanEqual:
  /** A case without parameters. */
  case Parameterless

  /** A case whose field is documented by its `@param` tag.
    *
    * @param value
    *   The text this case carries.
    */
  case Parameterized(value: String)

  /** A case whose field is documented by a lowercase `@param` fragment.
    *
    * @param count
    *   how many items, in total
    */
  case Counted(count: Int)

  override def toString: String = "TastyQueryEnum"
