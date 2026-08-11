package scalive.docs.pipeline

private[pipeline] object ApiSignatureFormatter:
  private val methodSignature = "^(extension def|def) ([^:]+): (.*)$".r

  def format(value: String): String =
    val withoutScalive =
      if value.startsWith("package ") then value
      else value.replace("scalive.package.", "").replace("scalive.", "")
    val shortened = withoutScalive
      .replace("scala.Predef.", "")
      .replace("scala.package.", "")
      .replace("scala.collection.immutable.", "")
      .replace("scala.", "")
      .replace("java.lang.", "")
      .replaceAll("[A-Za-z0-9_]+\\$package\\.", "")
      .replaceAll("[A-Za-z0-9_]+\\.this\\.", "")
      .replaceAll("([A-Za-z0-9_]+)\\$\\.this\\.", "$1.")
    val scalaSyntax = readableAppliedTypes(shortened)
    scalaSyntax match
      case methodSignature(keyword, name, declaredType) =>
        s"$keyword $name${renderMethodType(declaredType)}"
      case _ => scalaSyntax

  private def readableAppliedTypes(value: String): String =
    replaceAppliedType(value, "Function1") { arguments =>
      s"${arguments.head} => ${arguments(1)}"
    } match
      case functions =>
        replaceAppliedType(functions, "Tuple2") { arguments =>
          s"(${arguments.head}, ${arguments(1)})"
        }

  private def replaceAppliedType(
    value: String,
    typeName: String
  )(
    render: Vector[String] => String
  ): String =
    val prefix = s"$typeName["
    value.indexOf(prefix) match
      case -1    => value
      case start =>
        val open = start + typeName.length
        matchingBracket(value, open) match
          case Some(close) =>
            val arguments = splitTypeArguments(value.substring(open + 1, close))
              .map(readableAppliedTypes)
            if arguments.size != 2 then value
            else
              val replaced = value.take(start) + render(arguments) + value.drop(close + 1)
              replaceAppliedType(replaced, typeName)(render)
          case None => value

  private def matchingBracket(value: String, open: Int): Option[Int] =
    var depth = 0
    var index = open
    while index < value.length do
      value.charAt(index) match
        case '[' => depth += 1
        case ']' =>
          depth -= 1
          if depth == 0 then return Some(index)
        case _ => ()
      index += 1
    None

  private def splitTypeArguments(value: String): Vector[String] =
    var squareDepth = 0
    var roundDepth  = 0
    val separators  = Vector.newBuilder[Int]
    value.indices.foreach { index =>
      value.charAt(index) match
        case '['                                        => squareDepth += 1
        case ']'                                        => squareDepth -= 1
        case '('                                        => roundDepth += 1
        case ')'                                        => roundDepth -= 1
        case ',' if squareDepth == 0 && roundDepth == 0 => separators += index
        case _                                          => ()
    }
    separators.result() match
      case Vector(index) => Vector(value.take(index).trim, value.drop(index + 1).trim)
      case _             => Vector(value)

  private def renderMethodType(value: String): String =
    val parameterLists = Vector.newBuilder[String]
    var remaining      = value
    var parsing        = true
    while parsing && remaining.startsWith("(") do
      matchingParenthesis(remaining) match
        case Some(index) =>
          parameterLists += remaining.take(index + 1)
          remaining = remaining.drop(index + 1)
        case None => parsing = false
    val parameters = parameterLists.result()
    if parameters.nonEmpty && remaining.nonEmpty then s"${parameters.mkString}: $remaining"
    else s": $value"

  private def matchingParenthesis(value: String): Option[Int] =
    var depth = 0
    var index = 0
    while index < value.length do
      value.charAt(index) match
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if depth == 0 then return Some(index)
        case _ => ()
      index += 1
    None
end ApiSignatureFormatter
