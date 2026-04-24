import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.BooleanAsTrueFalseStringEncoder
import scalive.codecs.Encoder
import scalive.codecs.IntAsStringEncoder
import scalive.codecs.StringAsIsEncoder
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.components.Components
import scalive.defs.tags.HtmlTags

package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components:

  export _root_.scalive.streams.api.*
  export _root_.scalive.upload.api.*

  lazy val defer            = htmlAttr("defer", codecs.BooleanAsAttrPresenceEncoder)
  def rawHtml(html: String) = Mod.Content.Text(html, raw = true)
  def component(cid: Int, element: HtmlElement): Mod =
    Mod.Content.Component(cid, element)

  object link:
    def navigate(path: String, mods: Mod*): HtmlElement =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

    def patch(path: String, mods: Mod*): HtmlElement =
      a(href := path, phx.link := "patch", phx.linkState := "push", mods)

    def patch[A](to: LiveQueryCodec[A], value: A, mods: Mod*): HtmlElement =
      patch(requireHref(to, value, "link.patch"), mods*)

    def patchReplace(path: String, mods: Mod*): HtmlElement =
      a(href := path, phx.link := "patch", phx.linkState := "replace", mods)

    def patchReplace[A](to: LiveQueryCodec[A], value: A, mods: Mod*): HtmlElement =
      patchReplace(requireHref(to, value, "link.patchReplace"), mods*)

    private def requireHref[A](to: LiveQueryCodec[A], value: A, operation: String): String =
      to.href(value) match
        case Right(url) => url
        case Left(error) =>
          throw new IllegalArgumentException(s"Could not encode URL for $operation: ${error.message}", error)

  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsEncoder)
    private def phxAttrBool(suffix: String): HtmlAttr[Boolean] =
      new HtmlAttr(s"phx-$suffix", BooleanAsTrueFalseStringEncoder)
    private def phxAttrInt(suffix: String): HtmlAttr[Int] =
      new HtmlAttr(s"phx-$suffix", IntAsStringEncoder)
    private def phxAttrBinding(suffix: String): HtmlAttrBinding =
      new HtmlAttrBinding(s"phx-$suffix")
    private def dataPhxAttr(suffix: String): HtmlAttr[String] =
      dataAttr(s"phx-$suffix")

    private[scalive] lazy val session   = dataPhxAttr("session")
    private[scalive] lazy val main      = htmlAttr("data-phx-main", BooleanAsAttrPresenceEncoder)
    private[scalive] lazy val link      = dataPhxAttr("link")
    private[scalive] lazy val linkState = dataPhxAttr("link-state")

    // Click
    lazy val onClick     = phxAttrBinding("click")
    lazy val onClickAway = phxAttrBinding("click-away")

    // Focus
    lazy val onBlur       = phxAttrBinding("blur")
    lazy val onFocus      = phxAttrBinding("focus")
    lazy val onWindowBlur = phxAttrBinding("window-blur")

    // Keyboard
    lazy val onKeydown       = phxAttrBinding("keydown")
    lazy val onKeyup         = phxAttrBinding("keyup")
    lazy val onWindowKeydown = phxAttrBinding("window-keydown")
    lazy val onWindowKeyup   = phxAttrBinding("window-keyup")
    // For accepted values, see https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_key_values
    lazy val key = phxAttr("key")

    // Scroll
    lazy val onViewportTop    = phxAttrBinding("viewport-top")
    lazy val onViewportBottom = phxAttrBinding("viewport-bottom")

    // Upload
    lazy val dropTarget = phxAttr("drop-target")
    lazy val onProgress = phxAttrBinding("progress")

    // Form
    lazy val onChange      = phxAttrBinding("change")
    lazy val onSubmit      = phxAttrBinding("submit")
    lazy val autoRecover   = phxAttrBinding("auto-recover")
    lazy val triggerAction = phxAttrBool("trigger-action")

    // Button
    lazy val disableWith = phxAttr("disable-with")

    // Socket connection lifecycle
    lazy val onConnected    = phxAttrBinding("connected")
    lazy val onDisconnected = phxAttrBinding("disconnected")

    // DOM element lifecycle
    lazy val onMounted = phxAttrBinding("mounted")
    lazy val onRemove  = phxAttrBinding("remove")
    lazy val onUpdate  =
      new HtmlAttr["update" | "stream" | "ignore"](s"phx-update", Encoder(identity))

    // Client hooks
    lazy val hook = phxAttr("hook")

    // Rate limiting
    lazy val debounce = new HtmlAttr["blur" | Int](
      s"phx-debounce",
      Encoder {
        case _: "blur"  => "blur"
        case value: Int => value.toString
      }
    )
    lazy val throttle = phxAttrInt("throttle")

    def value(key: String) = phxAttr(s"value-$key")
    lazy val trackStatic   = htmlAttr("phx-track-static", BooleanAsAttrPresenceEncoder)
  end phx

  implicit def stringToMod(v: String): Mod            = Mod.Content.Text(v)
  implicit def htmlElementToMod(el: HtmlElement): Mod = Mod.Content.Tag(el)
end scalive
