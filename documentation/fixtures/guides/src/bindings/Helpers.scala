package bindings

import scalive.*

object Helpers:
  // docs:start bindings-passthrough
  def passthrough[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    articleTag(mods*)
  // docs:end bindings-passthrough

  // docs:start bindings-card
  def card[Msg](mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    articleTag(cls := "card", Mod.flatten(mods))

  val featured = card(cls := "featured", "Featured product")
  // docs:end bindings-card
