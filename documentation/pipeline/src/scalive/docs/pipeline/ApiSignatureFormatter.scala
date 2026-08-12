package scalive.docs.pipeline

private[pipeline] object ApiSignatureFormatter:
  private val methodSignature = "^(extension def|def|given) (.+?): (.*)$".r
  private val functionType    = "Function([0-9]+)\\[".r

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
    val scalaSyntax = shortened match
      case methodSignature(keyword, name, declaredType) =>
        s"$keyword $name${renderMethodType(declaredType)}"
      case _ => shortened
    readableAppliedTypes(scalaSyntax)

  private def readableAppliedTypes(value: String): String =
    val functions = replaceFunctionTypes(value)
    replaceAppliedType(functions, "Tuple2", expectedArguments = 2) { arguments =>
      s"(${arguments.head}, ${arguments(1)})"
    }

  private def replaceFunctionTypes(value: String): String =
    functionType.findFirstMatchIn(value) match
      case None        => value
      case Some(found) =>
        val arity = found.group(1).toInt
        val open  = found.end - 1
        matchingDelimiter(value, open, '[', ']') match
          case Some(close) =>
            val arguments = splitTypeArguments(value.substring(open + 1, close))
              .map(readableAppliedTypes)
            if arguments.size != arity + 1 then value
            else
              val parameters = arguments.init match
                case Vector(single) => single
                case values         => values.mkString("(", ", ", ")")
              val rendered = s"$parameters => ${arguments.last}"
              replaceFunctionTypes(value.take(found.start) + rendered + value.drop(close + 1))
          case None => value

  private def replaceAppliedType(
    value: String,
    typeName: String,
    expectedArguments: Int
  )(
    render: Vector[String] => String
  ): String =
    val prefix = s"$typeName["
    value.indexOf(prefix) match
      case -1    => value
      case start =>
        val open = start + typeName.length
        matchingDelimiter(value, open, '[', ']') match
          case Some(close) =>
            val arguments = splitTypeArguments(value.substring(open + 1, close))
              .map(readableAppliedTypes)
            if arguments.size != expectedArguments then value
            else
              val replaced = value.take(start) + render(arguments) + value.drop(close + 1)
              replaceAppliedType(replaced, typeName, expectedArguments)(render)
          case None => value

  private def matchingDelimiter(
    value: String,
    open: Int,
    opening: Char,
    closing: Char
  ): Option[Int] =
    var depth = 0
    var index = open
    while index < value.length do
      value.charAt(index) match
        case char if char == opening => depth += 1
        case char if char == closing =>
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
    val indices = separators.result()
    (Vector(-1) ++ indices).zip(indices :+ value.length).map { case (start, end) =>
      value.substring(start + 1, end).trim
    }

  private def renderMethodType(value: String): String =
    val clauses   = Vector.newBuilder[String]
    var remaining = value
    var parsing   = true
    while parsing && remaining.nonEmpty do
      val delimiters = remaining.head match
        case '[' => Some('[' -> ']')
        case '(' => Some('(' -> ')')
        case _   => None
      delimiters.flatMap { case (opening, closing) =>
        matchingDelimiter(remaining, 0, opening, closing)
      } match
        case Some(index) =>
          clauses += remaining.take(index + 1)
          remaining = remaining.drop(index + 1)
        case None => parsing = false
    val renderedClauses = clauses.result()
    if renderedClauses.nonEmpty && remaining.nonEmpty then
      s"${renderedClauses.mkString}: $remaining"
    else s": $value"

  def formatConstructor(value: String, defaultParameters: Set[String]): String =
    val normalized            = value.trim
    val withoutTypeParameters =
      if normalized.startsWith("[") then
        matchingDelimiter(normalized, 0, '[', ']').fold(normalized)(index =>
          normalized.drop(index + 1)
        )
      else normalized
    val clauses   = Vector.newBuilder[String]
    var remaining = withoutTypeParameters
    while remaining.startsWith("(") do
      matchingDelimiter(remaining, 0, '(', ')') match
        case Some(index) =>
          clauses += renderParameterClause(remaining.take(index + 1), defaultParameters)
          remaining = remaining.drop(index + 1)
        case None => return normalized
    clauses.result().mkString

  def markDefaultParameters(value: String, defaultParameters: Set[String]): String =
    if defaultParameters.isEmpty then value
    else
      val clauses   = Vector.newBuilder[String]
      var remaining = value
      var parsing   = true
      while parsing && remaining.nonEmpty do
        val delimiters = remaining.head match
          case '[' => Some('[' -> ']')
          case '(' => Some('(' -> ')')
          case _   => None
        delimiters.flatMap { case (opening, closing) =>
          matchingDelimiter(remaining, 0, opening, closing)
        } match
          case Some(index) =>
            val clause = remaining.take(index + 1)
            clauses += Option
              .when(clause.startsWith("("))(
                renderParameterClause(clause, defaultParameters)
              ).getOrElse(clause)
            remaining = remaining.drop(index + 1)
          case None => parsing = false
      clauses.result().mkString + remaining

  private def renderParameterClause(value: String, defaultParameters: Set[String]): String =
    val parameters = splitTypeArguments(value.drop(1).dropRight(1)).map { parameter =>
      val colon = parameter.indexOf(':')
      val name  =
        if colon < 0 then ""
        else parameter.take(colon).trim.stripPrefix("using ").stripPrefix("implicit ")
      if defaultParameters.contains(name) then s"$parameter = ..." else parameter
    }
    parameters.mkString("(", ", ", ")")
end ApiSignatureFormatter
