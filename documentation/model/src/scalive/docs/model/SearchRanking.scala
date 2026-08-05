package scalive.docs.model

import scala.collection.mutable.ArrayBuffer

object SearchRanking:
  def search(
    query: String,
    entries: Vector[SearchEntry],
    limit: Int = 20
  ): Vector[SearchEntry] =
    val queryTerms = terms(query)
    if queryTerms.isEmpty || limit <= 0 then Vector.empty
    else
      entries
        .flatMap(entry => score(entry, query, queryTerms).map(entry -> _))
        .sortBy { case (entry, score) =>
          (
            -score,
            kindOrder(entry.kind),
            asciiLower(entry.title),
            entry.route,
            entry.fragment.getOrElse(""),
            entry.id
          )
        }.take(limit)
        .map(_._1)

  private[model] def terms(value: String): Vector[String] =
    identifierTokens(value).map(asciiLower).filter(_.nonEmpty).distinct

  private[model] def aliases(value: String): Vector[String] =
    identifierTokens(value)
      .flatMap { token =>
        val whole    = asciiLower(token)
        val segments = token.split("[._$]").toVector.filter(_.nonEmpty)
        whole +: segments.flatMap(segment => asciiLower(segment) +: camelWords(segment))
      }.filter(_.nonEmpty).distinct

  private def score(
    entry: SearchEntry,
    query: String,
    queryTerms: Vector[String]
  ): Option[Int] =
    val title       = normalizedText(entry.title)
    val description = normalizedText(entry.description)
    val text        = normalizedText(entry.text)
    val titleTerms  = aliases(entry.title)
    val descTerms   = aliases(entry.description)
    val textTerms   = aliases(entry.text)

    val termScores = queryTerms.map { term =>
      fieldScore(term, title, titleTerms, 800, 600, 400) max
        fieldScore(term, description, descTerms, 200, 150, 100) max
        fieldScore(term, text, textTerms, 100, 75, 50)
    }

    Option.when(termScores.forall(_ > 0)) {
      val queryText   = queryTerms.mkString(" ")
      val phraseScore =
        if title == queryText then 5000
        else if title.startsWith(queryText) then 2500
        else if title.contains(queryText) then 1000
        else 0
      val caseScore =
        if entry.title == query.trim || entry.title.endsWith(s".${query.trim}") then 1500 else 0
      phraseScore + caseScore + termScores.sum
    }

  private def fieldScore(
    term: String,
    normalized: String,
    values: Vector[String],
    exact: Int,
    prefix: Int,
    contains: Int
  ): Int =
    if values.contains(term) then exact
    else if values.exists(_.startsWith(term)) then prefix
    else if normalized.contains(term) then contains
    else 0

  private def identifierTokens(value: String): Vector[String] =
    val tokens  = Vector.newBuilder[String]
    val current = new StringBuilder

    def complete(): Unit =
      if current.nonEmpty then
        tokens += current.result()
        current.clear()

    value.foreach { character =>
      if isIdentifierCharacter(character) then current.append(character)
      else complete()
    }
    complete()
    tokens.result()

  private def camelWords(value: String): Vector[String] =
    val words   = ArrayBuffer.empty[String]
    val current = new StringBuilder

    value.indices.foreach { index =>
      val character = value(index)
      val previous  = Option.when(index > 0)(value(index - 1))
      val next      = Option.when(index + 1 < value.length)(value(index + 1))
      val boundary  = isAsciiUpper(character) && current.nonEmpty &&
        (previous.exists(value => isAsciiLower(value) || isAsciiDigit(value)) ||
          next.exists(isAsciiLower))
      if boundary then
        words += asciiLower(current.result())
        current.clear()
      current.append(character)
    }
    if current.nonEmpty then words += asciiLower(current.result())
    words.toVector

  private def normalizedText(value: String): String =
    asciiLower(value).split("\\s+").filter(_.nonEmpty).mkString(" ")

  private def asciiLower(value: String): String = value.map { character =>
    if character >= 'A' && character <= 'Z' then (character + ('a' - 'A')).toChar
    else character
  }

  private def isIdentifierCharacter(value: Char): Boolean =
    isAsciiUpper(value) || isAsciiLower(value) || isAsciiDigit(value) ||
      value == '_' || value == '.' || value == '$'

  private def isAsciiUpper(value: Char): Boolean = value >= 'A' && value <= 'Z'

  private def isAsciiLower(value: Char): Boolean = value >= 'a' && value <= 'z'

  private def isAsciiDigit(value: Char): Boolean = value >= '0' && value <= '9'

  private def kindOrder(kind: SearchEntryKind): Int = kind match
    case SearchEntryKind.Page          => 0
    case SearchEntryKind.Heading       => 1
    case SearchEntryKind.Example       => 2
    case SearchEntryKind.ApiSymbol     => 3
    case SearchEntryKind.Compatibility => 4
end SearchRanking
