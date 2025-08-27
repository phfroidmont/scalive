import scalive.codecs.StringAsIsCodec
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.tags.HtmlTags

package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys:

  lazy val defer = htmlAttr("defer", codecs.BooleanAsOnOffStringCodec)

  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsCodec)
    private def phxAttrJson(suffix: String): HtmlAttrJsonValue =
      new HtmlAttrJsonValue(s"phx-$suffix")
    private def dataPhxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"data-phx-$suffix", StringAsIsCodec)

    private[scalive] lazy val session = dataPhxAttr("session")
    lazy val click                    = phxAttrJson("click")
    def value(key: String)            = phxAttr(s"value-$key")

  implicit def stringToMod(v: String): Mod            = Mod.Content.Text(v)
  implicit def htmlElementToMod(el: HtmlElement): Mod = Mod.Content.Tag(el)
  implicit def dynStringToMod(d: Dyn[String]): Mod    = Mod.Content.DynText(d)
