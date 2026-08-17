package scalive.docs.trace

import pprint.{PPrinter, Tree}

sealed private[docs] trait ProjectedScalaValue extends ProjectedScalaValue.Argument

private[docs] object ProjectedScalaValue:
  sealed trait Argument

  final case class NamedField private[ProjectedScalaValue] (
    name: String,
    value: ProjectedScalaValue)
      extends Argument

  final private case class Raw(name: String)            extends ProjectedScalaValue
  final private case class Text(value: String)          extends ProjectedScalaValue
  final private case class Number(value: String)        extends ProjectedScalaValue
  final private case class BooleanValue(value: Boolean) extends ProjectedScalaValue
  private case object NullValue                         extends ProjectedScalaValue
  private case object Wildcard                          extends ProjectedScalaValue
  final private case class Constructor(name: String, arguments: Vector[Argument])
      extends ProjectedScalaValue
  final private case class Collection(name: String, values: Vector[ProjectedScalaValue])
      extends ProjectedScalaValue

  def name(value: String): ProjectedScalaValue =
    Raw(checkedName(value))

  val wildcard: ProjectedScalaValue  = Wildcard
  val nullValue: ProjectedScalaValue = NullValue

  def string(value: String): ProjectedScalaValue     = Text(value)
  def boolean(value: Boolean): ProjectedScalaValue   = BooleanValue(value)
  def number(value: Int): ProjectedScalaValue        = Number(value.toString)
  def number(value: Long): ProjectedScalaValue       = Number(value.toString)
  def number(value: BigInt): ProjectedScalaValue     = Number(value.toString)
  def number(value: BigDecimal): ProjectedScalaValue = Number(value.toString)

  def field(name: String, value: ProjectedScalaValue): NamedField =
    NamedField(checkedSimpleName(name), value)

  def constructor(name: String, arguments: Argument*): ProjectedScalaValue =
    Constructor(checkedName(name), arguments.toVector)

  def collection(name: String, values: ProjectedScalaValue*): ProjectedScalaValue =
    Collection(checkedName(name), values.toVector)

  def list(values: ProjectedScalaValue*): ProjectedScalaValue   = collection("List", values*)
  def vector(values: ProjectedScalaValue*): ProjectedScalaValue = collection("Vector", values*)
  def seq(values: ProjectedScalaValue*): ProjectedScalaValue    = collection("Seq", values*)

  private val SimpleName = "[A-Za-z_$][A-Za-z0-9_$]*".r

  private def checkedSimpleName(value: String): String =
    require(SimpleName.matches(value), s"Invalid projected Scala name: $value")
    value

  private def checkedName(value: String): String =
    require(value.split('.').forall(SimpleName.matches), s"Invalid projected Scala name: $value")
    value

  private[trace] def tree(value: ProjectedScalaValue): Tree = value match
    case Raw(name)                    => Tree.Literal(name)
    case Text(value)                  => Tree.Literal(quoted(value))
    case Number(value)                => Tree.Literal(value)
    case BooleanValue(value)          => Tree.Literal(if value then "true" else "false")
    case NullValue                    => Tree.Literal("null")
    case Wildcard                     => Tree.Literal("_")
    case Constructor(name, arguments) =>
      Tree.Apply(name, arguments.iterator.map(argumentTree))
    case Collection(name, values) =>
      Tree.Apply(name, values.iterator.map(tree))

  private def argumentTree(argument: Argument): Tree = argument match
    case value: ProjectedScalaValue => tree(value)
    case NamedField(name, value)    => Tree.KeyValue(name, tree(value))

  private def quoted(value: String): String =
    val result = new StringBuilder(value.length + 2).append('"')
    value.foreach {
      case '"'                              => result.append("\\\"")
      case '\\'                             => result.append("\\\\")
      case '\b'                             => result.append("\\b")
      case '\f'                             => result.append("\\f")
      case '\n'                             => result.append("\\n")
      case '\r'                             => result.append("\\r")
      case '\t'                             => result.append("\\t")
      case character if character.isControl =>
        result.append(f"\\u${character.toInt}%04x")
      case character => result.append(character)
    }
    result.append('"').result()
end ProjectedScalaValue

private[docs] object ProjectedScalaValueFormatter:
  private val Width  = 48
  private val Height = 16
  private val Indent = 2

  private val printer = PPrinter.BlackWhite.copy(
    defaultWidth = Width,
    defaultHeight = Height,
    defaultIndent = Indent,
    additionalHandlers = { case value: ProjectedScalaValue => ProjectedScalaValue.tree(value) }
  )

  def format(value: ProjectedScalaValue): String =
    printer(value).plainText
