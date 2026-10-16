package scalive.testing

import scala.jdk.CollectionConverters.*

import org.jsoup.nodes.Element

/** Form ownership as the browser decides it, which is what `new FormData(form)` reads.
  *
  * A control with a `form` attribute belongs to the form with that id, or to no form when there is
  * none, even when it is nested in another form. A control without one belongs to its closest
  * ancestor form.
  */
private[testing] object FormOwner:
  /** The named controls of the document whose owner is `form`, in tree order. */
  def controls(form: Element): Vector[Element] =
    form
      .root()
      .select("button[name], input[name], select[name], textarea[name]").asScala.toVector
      .filter(control => owner(control).exists(_ eq form))

  private def owner(control: Element): Option[Element] =
    if control.hasAttr("form") then
      val id = control.attr("form")
      Option
        .when(id.nonEmpty)(control.root().getElementById(id))
        .flatMap(Option(_))
        .filter(_.tagName == "form")
    else control.parents().asScala.find(_.tagName == "form")
