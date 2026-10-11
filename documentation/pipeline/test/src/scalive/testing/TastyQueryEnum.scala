package scalive.testing

enum TastyQueryEnum derives CanEqual:
  case Parameterless

  /** A case whose field is documented by its `@param` tag.
    *
    * @param value
    *   The text this case carries.
    */
  case Parameterized(value: String)

  override def toString: String = "TastyQueryEnum"
