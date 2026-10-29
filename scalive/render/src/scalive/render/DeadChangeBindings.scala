package scalive.render

import scalive.Escaping

/** Finds `phx-change` bindings that the Phoenix client never sends.
  *
  * The client reads a change binding only from the control that fired the event or from that
  * control's form, and fails when the control has no form. A binding is therefore dead on any
  * element other than a form or a control, and on a control with neither an ancestor form nor a
  * `form` attribute. The `form` attribute's id is not resolved: the form may be outside the tree.
  */
private[scalive] object DeadChangeBindings:
  private val ChangeAttribute = "phx-change"
  private val Controls        = Set("input", "select", "textarea")
  private val Described       = Vector("id", "name", "class")

  /** Returns each dead binding's element, described by its tag, id, name, and class. */
  def find(tree: EvaluatedTree): Vector[String] =
    val dead = Vector.newBuilder[String]

    def visit(node: EvaluatedNode, inForm: Boolean): Unit = node match
      case element: EvaluatedNode.Element =>
        val isForm = element.tag == "form"
        if has(element, ChangeAttribute) && !isForm && !(isControl(element) && owned(
            element,
            inForm
          ))
        then dead += describe(element)
        element.children.foreach(visit(_, inForm || isForm))
      case _: EvaluatedNode.Text        => ()
      case flash: EvaluatedNode.Flash   => flash.child.foreach(visit(_, inForm))
      case choice: EvaluatedNode.Choice => choice.children.foreach(visit(_, inForm))
      case keyed: EvaluatedNode.Keyed   => keyed.rows.foreach(row => visit(row.child, inForm))
      case stream: EvaluatedNode.Stream => stream.rows.foreach(row => visit(row.child, inForm))
      case component: EvaluatedNode.Component =>
        component.resolution.foreach(value => visit(value.child.root, inForm))
      case nested: EvaluatedNode.Nested =>
        nested.resolution.foreach(_.child.foreach(tree => visit(tree.root, inForm)))

    visit(tree.root, inForm = false)
    dead.result()

  // A custom element may be form-associated, and then has a form owner like a control.
  private def isControl(element: EvaluatedNode.Element): Boolean =
    Controls(element.tag) || element.tag.contains('-')

  private def owned(element: EvaluatedNode.Element, inForm: Boolean): Boolean =
    inForm || has(element, "form")

  private def has(element: EvaluatedNode.Element, name: String): Boolean =
    element.attributes.exists(attribute => attribute.name == name && attribute.value.isDefined)

  private def describe(element: EvaluatedNode.Element): String =
    val attributes = Described.flatMap(name =>
      element.attributes.find(_.name == name).flatMap(_.value).collect {
        case AttributeValue.Text(value)                => s" $name=\"${Escaping.escape(value)}\""
        case AttributeValue.Composite(static, dynamic) =>
          val value = if dynamic.isEmpty then static else s"$static $dynamic"
          s" $name=\"${Escaping.escape(value)}\""
      }
    )
    s"<${element.tag}${attributes.mkString}>"
end DeadChangeBindings
