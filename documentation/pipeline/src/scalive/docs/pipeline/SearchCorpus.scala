package scalive.docs.pipeline

import scala.collection.mutable.ArrayBuffer

import scalive.docs.model.*

private[pipeline] object SearchCorpus:
  def build(
    pages: Vector[Page],
    apiReference: ApiReference,
    examples: Vector[ExampleDefinition]
  ): Either[Vector[String], Vector[SearchEntry]] =
    val routes        = pages.map(_.route).toSet
    val authoredPages = pages.collect { case page @ Page(_, _, _: PageSource.Authored, _, _) =>
      page
    }
    val exampleById = examples.map(example => example.descriptor.id -> example).toMap
    val pageEntries = authoredPages.flatMap(entriesForPage(_, exampleById))
    val apiEntries  = apiReference.symbols.filter(symbol => routes(symbol.route)).map(apiEntry)
    val entries     = (pageEntries ++ apiEntries).sortBy(_.id)
    val errors      = validate(entries, pages)

    Either.cond(errors.isEmpty, entries, errors)

  private def entriesForPage(
    page: Page,
    examples: Map[String, ExampleDefinition]
  ): Vector[SearchEntry] =
    val pageEntry = SearchEntry(
      id = s"page:${page.route}",
      kind = SearchEntryKind.Page,
      title = page.metadata.title,
      description = page.metadata.description,
      route = page.route,
      fragment = None,
      section = page.metadata.section,
      text = prose(page.content)
    )
    val headings = collectHeadings(page.content).map { heading =>
      val title = inlineText(heading.content)
      SearchEntry(
        id = s"heading:${page.route}#${heading.id}",
        kind = SearchEntryKind.Heading,
        title = title,
        description = page.metadata.title,
        route = page.route,
        fragment = Some(heading.id),
        section = page.metadata.section,
        text = s"$title ${page.metadata.title} ${page.metadata.description}"
      )
    }
    val directives = collectDirectives(page.content).map {
      case (SearchEntryKind.Example, id) =>
        val example = examples.getOrElse(
          id,
          throw new IllegalArgumentException(s"Unknown validated example: $id")
        )
        exampleEntry(page, example)
      case (SearchEntryKind.Compatibility, id) =>
        directiveEntry(page, SearchEntryKind.Compatibility, id, "compatibility")
      case (kind, _) =>
        throw new IllegalArgumentException(s"Unsupported directive search kind: $kind")
    }
    (pageEntry +: headings) ++ directives
  end entriesForPage

  private def exampleEntry(page: Page, example: ExampleDefinition): SearchEntry =
    val descriptor = example.descriptor
    val fragment   = s"example-${descriptor.id}"
    SearchEntry(
      id = s"example:${page.route}#$fragment",
      kind = SearchEntryKind.Example,
      title = descriptor.title,
      description = descriptor.description,
      route = page.route,
      fragment = Some(fragment),
      section = page.metadata.section,
      text =
        (Vector(descriptor.id, descriptor.title, descriptor.description, page.metadata.title) ++
          descriptor.topics ++ descriptor.aliases).mkString(" ")
    )

  private def directiveEntry(
    page: Page,
    kind: SearchEntryKind,
    id: String,
    prefix: String
  ): SearchEntry =
    val title    = humanize(id)
    val fragment = s"$prefix-$id"
    SearchEntry(
      id = s"$prefix:${page.route}#$fragment",
      kind = kind,
      title = title,
      description = s"$title ${kindLabel(kind)} on ${page.metadata.title}.",
      route = page.route,
      fragment = Some(fragment),
      section = page.metadata.section,
      text = s"$id ${id.replace('-', ' ')} ${page.metadata.title}"
    )

  private def apiEntry(symbol: ApiSymbol): SearchEntry =
    SearchEntry(
      id = s"api:${symbol.id}",
      kind = SearchEntryKind.ApiSymbol,
      title = symbol.qualifiedName,
      description = symbol.summary,
      route = symbol.route,
      fragment = symbol.fragment,
      section = Section.Api,
      text = (Vector(symbol.name, symbol.qualifiedName, symbol.summary) ++
        symbol.signatures.map(_.signature)).mkString(" ")
    )

  private def validate(entries: Vector[SearchEntry], pages: Vector[Page]): Vector[String] =
    val errors  = ArrayBuffer.empty[String]
    val anchors = pages.map(page => page.route -> pageAnchors(page)).toMap

    entries.groupBy(_.id).toVector.sortBy(_._1).foreach { case (id, matches) =>
      if matches.sizeIs > 1 then errors += s"duplicate search entry id '$id'."
    }
    pages.foreach { page =>
      val duplicates = allAnchors(page).groupBy(identity).collect {
        case (anchor, matches) if matches.sizeIs > 1 => anchor
      }
      duplicates.toVector.sorted.foreach { anchor =>
        errors += s"${pageName(page)}: duplicate rendered anchor '$anchor'."
      }
    }
    entries.foreach { entry =>
      anchors.get(entry.route) match
        case None => errors += s"search entry '${entry.id}' targets unknown route '${entry.route}'."
        case Some(routeAnchors) =>
          entry.fragment.foreach { fragment =>
            if !routeAnchors(fragment) then
              errors +=
                s"search entry '${entry.id}' targets unknown anchor '${entry.route}#$fragment'."
          }
    }
    errors.toVector.distinct.sorted

  private def pageAnchors(page: Page): Set[String] = allAnchors(page).toSet

  private def allAnchors(page: Page): Vector[String] =
    flattenOutline(page.outline.items).map(_.id) ++ collectDirectives(page.content).map {
      case (SearchEntryKind.Example, id)       => s"example-$id"
      case (SearchEntryKind.Compatibility, id) => s"compatibility-$id"
      case (kind, _)                           =>
        throw new IllegalArgumentException(s"Unsupported directive search kind: $kind")
    }

  private def flattenOutline(items: Vector[OutlineItem]): Vector[OutlineItem] =
    items.flatMap(item => item +: flattenOutline(item.children))

  private def collectHeadings(blocks: Vector[Block]): Vector[Block.Heading] =
    blocks.flatMap {
      case heading: Block.Heading       => Vector(heading)
      case Block.BulletList(items)      => items.flatMap(item => collectHeadings(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectHeadings(item.content))
      case Block.Quote(content)         => collectHeadings(content)
      case Block.Callout(_, _, content) => collectHeadings(content)
      case _                            => Vector.empty
    }

  private def collectDirectives(
    blocks: Vector[Block]
  ): Vector[(SearchEntryKind, String)] =
    blocks.flatMap {
      case Block.ExampleRef(id)         => Vector(SearchEntryKind.Example -> id)
      case Block.CompatibilityRef(id)   => Vector(SearchEntryKind.Compatibility -> id)
      case Block.BulletList(items)      => items.flatMap(item => collectDirectives(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectDirectives(item.content))
      case Block.Quote(content)         => collectDirectives(content)
      case Block.Callout(_, _, content) => collectDirectives(content)
      case _                            => Vector.empty
    }

  private def prose(blocks: Vector[Block]): String =
    blocks
      .flatMap {
        case Block.Paragraph(content)     => Vector(inlineText(content))
        case Block.Heading(_, _, content) => Vector(inlineText(content))
        case Block.BulletList(items)      => items.flatMap(item => Vector(prose(item.content)))
        case Block.OrderedList(_, items)  => items.flatMap(item => Vector(prose(item.content)))
        case Block.Quote(content)         => Vector(prose(content))
        case Block.Table(header, rows)    =>
          (header ++ rows.flatMap(_.cells)).map(cell => inlineText(cell.content))
        case Block.Image(_, alternative, imageTitle) => Vector(alternative) ++ imageTitle
        case Block.Callout(_, title, content)        => title.toVector :+ prose(content)
        case Block.ExampleRef(id)                    => Vector(id.replace('-', ' '))
        case Block.CompatibilityRef(id)              => Vector(id.replace('-', ' '))
        case _                                       => Vector.empty
      }.map(_.trim).filter(_.nonEmpty).mkString(" ")

  private def inlineText(inlines: Vector[Inline]): String = inlines.map {
    case Inline.Text(value)         => value
    case Inline.Emphasis(content)   => inlineText(content)
    case Inline.Strong(content)     => inlineText(content)
    case Inline.Strike(content)     => inlineText(content)
    case Inline.Code(value)         => value
    case Inline.Link(content, _, _) => inlineText(content)
    case Inline.LineBreak           => " "
  }.mkString

  private def humanize(id: String): String =
    id.split('-').toVector.filter(_.nonEmpty).mkString(" ") match
      case ""    => ""
      case value => s"${value.head.toUpper}${value.tail}"

  private def kindLabel(kind: SearchEntryKind): String = kind match
    case SearchEntryKind.Example       => "example"
    case SearchEntryKind.Compatibility => "compatibility entry"
    case _ => throw new IllegalArgumentException(s"Unsupported directive search kind: $kind")

  private def pageName(page: Page): String = page.source match
    case PageSource.Authored(location) => location.path
    case PageSource.GeneratedApi(id)   => s"generated API page '$id'"
end SearchCorpus
