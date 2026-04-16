package scalive.defs.components

import scalive.*

trait Components:
  private lazy val ariaHidden = htmlAttr("aria-hidden", codecs.BooleanAsTrueFalseStringEncoder)

  def focusWrap(id: String, mods: Mod*)(content: Mod*): HtmlElement =
    val startSentinel = span(idAttr := s"$id-start", tabIndex := 0, ariaHidden := true)
    val endSentinel   = span(idAttr := s"$id-end", tabIndex := 0, ariaHidden := true)

    div(
      Vector(idAttr := id, phx.hook := "Phoenix.FocusWrap") ++
        mods ++
        Vector(Mod.Content.Tag(startSentinel)) ++
        content ++
        Vector(Mod.Content.Tag(endSentinel))
    )
