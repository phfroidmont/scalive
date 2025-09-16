import scalive.codecs.BooleanAsAttrPresenceCodec
import scalive.codecs.StringAsIsCodec
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.tags.HtmlTags

package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys:

  lazy val defer = htmlAttr("defer", codecs.BooleanAsAttrPresenceCodec)

  object link:
    def navigate(path: String, mods: Mod*): HtmlElement =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsCodec)
    private def phxAttrJson(suffix: String): HtmlAttrJsonValue =
      new HtmlAttrJsonValue(s"phx-$suffix")
    private def dataPhxAttr(suffix: String): HtmlAttr[String] =
      dataAttr(s"phx-$suffix")

    private[scalive] lazy val session   = dataPhxAttr("session")
    private[scalive] lazy val main      = htmlAttr("data-phx-main", BooleanAsAttrPresenceCodec)
    private[scalive] lazy val link      = dataPhxAttr("link")
    private[scalive] lazy val linkState = dataPhxAttr("link-state")
    lazy val click                      = phxAttrJson("click")
    def value(key: String)              = phxAttr(s"value-$key")
    lazy val trackStatic                = htmlAttr("phx-track-static", BooleanAsAttrPresenceCodec)

  implicit def stringToMod(v: String): Mod            = Mod.Content.Text(v)
  implicit def htmlElementToMod(el: HtmlElement): Mod = Mod.Content.Tag(el)
  implicit def dynStringToMod(d: Dyn[String]): Mod    = Mod.Content.DynText(d)
