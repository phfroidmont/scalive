package scalive.render

import scalive.Escaping

/** Serializes an [[EvaluatedTree]] as full HTML.
  *
  * Attribute values and ordinary text are escaped here; explicitly raw text is emitted unchanged.
  * Layouts, bootstrap metadata, CSRF injection, and transport concerns remain outside this
  * renderer.
  */
object HtmlRenderer:
  def render(tree: EvaluatedTree, includeDoctype: Boolean = false): String =
    val output = StringBuilder()
    if includeDoctype then output.append("<!doctype html>")
    appendNode(tree.root, output)
    output.result()

  private def appendNode(node: EvaluatedNode, output: StringBuilder): Unit =
    node match
      case element: EvaluatedNode.Element => appendElement(element, output)
      case text: EvaluatedNode.Text       =>
        if text.raw then output.append(text.value)
        else output.append(Escaping.escape(text.value))
      case flash: EvaluatedNode.Flash   => flash.child.foreach(appendElement(_, output))
      case choice: EvaluatedNode.Choice => choice.child.foreach(appendNode(_, output))
      case keyed: EvaluatedNode.Keyed => keyed.rows.foreach(row => appendElement(row.child, output))
      case component: EvaluatedNode.Component =>
        component.resolution match
          case Some(value) => appendElement(value.child.root, output)
          case None        =>
            throw IllegalStateException(s"component ${component.id.value} is unresolved")
      case _: EvaluatedNode.Nested | _: EvaluatedNode.Stream => ()

  private def appendElement(element: EvaluatedNode.Element, output: StringBuilder): Unit =
    val _ = output.append('<').append(element.tag)
    element.attributes.foreach { attribute =>
      attribute.value.foreach {
        case AttributeValue.Presence    => output.append(' ').append(attribute.name)
        case AttributeValue.Text(value) =>
          output
            .append(' ').append(attribute.name).append("=\"")
            .append(Escaping.escape(value)).append('"')
        case AttributeValue.ComponentTarget(_) =>
          throw IllegalStateException(
            s"component target attribute '${attribute.name}' requires protocol serialization"
          )
      }
    }
    output.append('>')

    if !element.void then
      element.children.foreach(appendNode(_, output))
      val _ = output.append("</").append(element.tag).append('>')
end HtmlRenderer
