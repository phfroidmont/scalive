import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.tags.HtmlTags

package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys:
  implicit def stringToMod(v: String): Mod            = Mod.Content.Text(v)
  implicit def htmlElementToMod(el: HtmlElement): Mod = Mod.Content.Tag(el)
  implicit def dynStringToMod(d: Dyn[String]): Mod    = Mod.Content.DynText(d)
