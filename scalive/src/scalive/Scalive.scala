import scala.reflect.ClassTag

import scalive.codecs.BooleanAsAttrPresenceEncoder
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

  lazy val defer                          = htmlAttr("defer", codecs.BooleanAsAttrPresenceEncoder)
  def rawHtml(html: String): Mod[Nothing] = Mod.Content.Text(html, raw = true)
  def component[Msg](cid: Int, element: HtmlElement[Msg]): Mod[Msg] =
    Mod.Content.Component(cid, element)
  def component[C <: LiveComponent[?, ?, ?]: ClassTag](
    message: LiveComponent.MsgOf[C]
  ): ComponentTargetMessage =
    ComponentTargetMessage(summon[ClassTag[C]].runtimeClass, message)

  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props
  ): Mod[Nothing] =
    Mod.Content.LiveComponent(LiveComponentSpec(component, id, props))

  def liveView[Msg: ClassTag, Model](
    id: String,
    liveView: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod[Nothing] =
    Mod.Content.LiveView(
      NestedLiveViewSpec(id, () => liveView, summon[ClassTag[Msg]], sticky, linkParentOnCrash)
    )

  def flash(kind: String)(f: String => HtmlElement[Nothing]): Mod[Nothing] =
    Mod.Content.Flash(kind, f)

  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Int,
    props: Props
  ): Mod[Nothing] =
    liveComponent(component, id.toString, props)

  private lazy val portalTemplateTag = HtmlTag("template")
  private lazy val phxPortal         = dataAttr("phx-portal")

  def portal[Msg](
    id: String,
    target: String,
    container: String = "div",
    wrapperClass: Option[String] = None
  )(
    mods: (Mod[Msg] | IterableOnce[Mod[Msg]])*
  ): HtmlElement[Msg] =
    require(
      Escaping.validTag(container),
      s"portal container must be a valid HTML tag, got '$container'"
    )
    val contentMods = mods.toVector.flatMap {
      case mod: Mod[Msg]                => Some(mod)
      case mods: IterableOnce[Mod[Msg]] => mods
    }
    val wrapperMods = Vector.newBuilder[Mod[Msg]]
    wrapperMods += (idAttr := s"_lv_portal_wrap_$id")
    wrapperClass.foreach(value => wrapperMods += (cls := value))
    wrapperMods ++= contentMods

    portalTemplateTag(
      idAttr    := id,
      phxPortal := target,
      HtmlTag(container)(wrapperMods.result())
    )

  object link:
    def navigate[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

    def patch[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "push", mods)

    def patch[A, Msg](to: LiveQueryCodec[A], value: A, mods: Mod[Msg]*): HtmlElement[Msg] =
      patch(requireHref(to, value, "link.patch"), mods*)

    def patchReplace[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "replace", mods)

    def patchReplace[A, Msg](to: LiveQueryCodec[A], value: A, mods: Mod[Msg]*): HtmlElement[Msg] =
      patchReplace(requireHref(to, value, "link.patchReplace"), mods*)

    private def requireHref[A](to: LiveQueryCodec[A], value: A, operation: String): String =
      to.href(value) match
        case Right(url)  => url
        case Left(error) =>
          throw new IllegalArgumentException(
            s"Could not encode URL for $operation: ${error.message}",
            error
          )

  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsEncoder)
    private def phxAttrBool(suffix: String): HtmlAttr[Boolean] =
      new HtmlAttr(s"phx-$suffix", BooleanAsAttrPresenceEncoder)
    private def phxAttrInt(suffix: String): HtmlAttr[Int] =
      new HtmlAttr(s"phx-$suffix", IntAsStringEncoder)
    private def phxAttrBinding(suffix: String): HtmlAttrBinding =
      new HtmlAttrBinding(s"phx-$suffix")
    private def dataPhxAttr(suffix: String): HtmlAttr[String] =
      dataAttr(s"phx-$suffix")

    private[scalive] lazy val session   = dataPhxAttr("session")
    private[scalive] lazy val main      = htmlAttr("data-phx-main", BooleanAsAttrPresenceEncoder)
    private[scalive] lazy val parentId  = dataPhxAttr("parent-id")
    private[scalive] lazy val childId   = dataPhxAttr("child-id")
    private[scalive] lazy val sticky    = htmlAttr("data-phx-sticky", BooleanAsAttrPresenceEncoder)
    private[scalive] lazy val link      = dataPhxAttr("link")
    private[scalive] lazy val linkState = dataPhxAttr("link-state")
    private[scalive] lazy val component = dataPhxAttr("component")

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
    lazy val onChange                                        = phxAttrBinding("change")
    lazy val onSubmit                                        = phxAttrBinding("submit")
    def onChangeForm[Msg](f: FormData => Msg): Mod.Attr[Msg] =
      onChange.form(f)
    def onChangeForm[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg] =
      onChange.form(codec)(f)
    def onSubmitForm[Msg](f: FormData => Msg): Mod.Attr[Msg] =
      onSubmit.form(f)
    def onSubmitForm[A, Msg](codec: FormCodec[A])(f: FormEvent[A] => Msg): Mod.Attr[Msg] =
      onSubmit.form(codec)(f)
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
    lazy val hook                          = phxAttr("hook")
    lazy val clearFlash: Mod.Attr[Nothing] = phxAttr("click") := "lv:clear-flash"
    def target[Msg](ref: ComponentRef[Msg]): Mod.Attr[Nothing] =
      phxAttr("target") := ref.toString
    def target(selector: String): Mod.Attr[Nothing] =
      phxAttr("target") := selector

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

  implicit def stringToMod(v: String): Mod[Nothing]                  = Mod.Content.Text(v)
  implicit def htmlElementToMod[Msg](el: HtmlElement[Msg]): Mod[Msg] = Mod.Content.Tag(el)
end scalive
