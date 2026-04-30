package scalive

/** Structured form field path parsed from browser names such as `user[address][city]`. */
final case class FormPath(segments: Vector[String]):
  def /(segment: String): FormPath =
    copy(segments = segments :+ segment)

  def array: FormPath =
    copy(segments = segments :+ "")

  def isEmpty: Boolean  = segments.isEmpty
  def nonEmpty: Boolean = segments.nonEmpty

  def name: String =
    segments.headOption.fold("") { first =>
      first + segments.tail.map(segment => s"[$segment]").mkString
    }

  override def toString: String = name

object FormPath:
  val empty: FormPath = FormPath(Vector.empty)

  def apply(first: String, rest: String*): FormPath =
    FormPath((first +: rest).filter(_.nonEmpty).toVector)

  def parse(name: String): FormPath =
    if name.isEmpty then empty
    else
      val segments = Vector.newBuilder[String]
      val current  = new StringBuilder()
      var index    = 0

      def pushCurrent(): Unit =
        if current.nonEmpty then
          segments += current.toString
          current.clear()

      while index < name.length do
        name.charAt(index) match
          case '[' =>
            pushCurrent()
            index = index + 1
            while index < name.length && name.charAt(index) != ']' do
              current.append(name.charAt(index))
              index = index + 1
            pushCurrent()
          case ']'  =>
          case char =>
            current.append(char)
        index = index + 1

      pushCurrent()
      FormPath(segments.result().filter(_.nonEmpty))
end FormPath
