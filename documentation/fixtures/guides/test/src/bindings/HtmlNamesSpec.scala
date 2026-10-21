package bindings

import scala.io.Source

import zio.test.*

import scalive.{HtmlAttr, HtmlTag}
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.tags.HtmlTags

object HtmlNamesSpec extends ZIOSpecDefault:
  private val dsl = Class.forName("scalive.package$").getField("MODULE$").get(null)

  // Each HTML name whose Scalive members all differ from it, with those members.
  private def renamed[A](definitions: Class[?], valueType: Class[A])(name: A => String) =
    definitions.getMethods.toVector
      .filter(method => method.getParameterCount == 0 && valueType.isAssignableFrom(method.getReturnType))
      .map(method => name(valueType.cast(method.invoke(dsl))) -> method.getName)
      .groupMap(_._1)(_._2)
      .collect { case (html, members) if !members.contains(html) => html -> members.sorted }

  private val guide =
    val root   = sys.env.getOrElse("MILL_WORKSPACE_ROOT", sys.error("MILL_WORKSPACE_ROOT is not set"))
    val source = Source.fromFile(s"$root/documentation/content/guides/html-dsl-and-event-bindings.md")
    try source.mkString
    finally source.close()

  private def row(html: String, members: Vector[String]) =
    s"| `$html` | ${members.map(member => s"`$member`").mkString(", ")} |"

  def spec = suite("Names that differ from HTML")(
    test("the guide's table lists every tag and attribute whose name differs from HTML") {
      val tags       = renamed(classOf[HtmlTags], classOf[HtmlTag])(_.name)
        .map((html, members) => s"<$html>" -> members)
      val attributes =
        renamed(classOf[HtmlAttrs], classOf[HtmlAttr[?]])(_.name) ++
          renamed(classOf[ComplexHtmlKeys], classOf[HtmlAttr[?]])(_.name)
      val rows    = (tags.toVector.sortBy(_._1) ++ attributes.toVector.sortBy(_._1)).map(row)
      val missing = rows.filterNot(guide.contains)

      assertTrue(tags.contains("<textarea>"), attributes.contains("class"), missing.isEmpty)
    }
  )
end HtmlNamesSpec
