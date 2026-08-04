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

  def liveTitle(
    pageTitle: Option[String],
    default: String,
    prefix: String = "",
    suffix: String = ""
  ): HtmlElement[Nothing] =
    val title = normalizePageTitle(pageTitle).getOrElse(default)
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      s"$prefix$title$suffix"
    )

  private[scalive] def normalizePageTitle(pageTitle: Option[String]): Option[String] =
    pageTitle.filter(_.trim.nonEmpty)

  def component[Props, Msg, Model](
    liveComponent: LiveComponent[Props, Msg, Model],
    id: String
  ): LiveComponentInstance[Props, Msg, Model] =
    LiveComponentInstance(liveComponent, id)

  def liveComponent[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props
  ): Mod[Nothing] =
    Mod.Content.LiveComponent(LiveComponentSpec(component, id, props))

  def liveView[Msg: LiveMessageTag, Model](
    id: String,
    liveView: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod[Nothing] =
    Mod.Content.LiveView(
      NestedLiveViewSpec(
        id,
        () => liveView,
        summon[LiveMessageTag[Msg]].classTag,
        sticky,
        linkParentOnCrash
      )
    )

  object flash:
    def apply(kind: FlashKind)(f: String => HtmlElement[Nothing]): Mod[Nothing] =
      Mod.Content.Flash(kind.value, f)

    lazy val clearOnClick: Mod.Attr[Nothing] = phx.click := "lv:clear-flash"

    def clearOnClick(kind: FlashKind): Vector[Mod.Attr[Nothing]] =
      Vector(clearOnClick, phx.value("key") := kind.value)

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
    target: DomSelector,
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
      phxPortal := target.requiredValue,
      HtmlTag(container)(wrapperMods.result())
    )

  object link:
    def pushNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.href, mods*)

    def pushNavigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "push", mods)

    def replaceNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.href, mods*)

    def replaceNavigateUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "redirect", phx.linkState := "replace", mods)

    def pushPatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.href, mods*)

    def pushPatchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "push", mods)

    def replacePatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.href, mods*)

    def replacePatchUnsafe[Msg](path: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := path, phx.link := "patch", phx.linkState := "replace", mods)

  object phx:
    private def phxAttr(suffix: String): HtmlAttr[String] =
      new HtmlAttr(s"phx-$suffix", StringAsIsEncoder)
    private def phxAttrBool(suffix: String): HtmlAttr[Boolean] =
      new HtmlAttr(s"phx-$suffix", BooleanAsAttrPresenceEncoder)
    private def phxAttrInt(suffix: String): HtmlAttr[Int] =
      new HtmlAttr(s"phx-$suffix", IntAsStringEncoder)
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

    lazy val click          = phxAttr("click")
    lazy val clickAway      = phxAttr("click-away")
    lazy val blur           = phxAttr("blur")
    lazy val focus          = phxAttr("focus")
    lazy val windowBlur     = phxAttr("window-blur")
    lazy val windowFocus    = phxAttr("window-focus")
    lazy val keyDown        = phxAttr("keydown")
    lazy val keyUp          = phxAttr("keyup")
    lazy val windowKeyDown  = phxAttr("window-keydown")
    lazy val windowKeyUp    = phxAttr("window-keyup")
    lazy val key            = phxAttr("key")
    lazy val viewportTop    = phxAttr("viewport-top")
    lazy val viewportBottom = phxAttr("viewport-bottom")
    lazy val progress       = phxAttr("progress")
    lazy val change         = phxAttr("change")
    lazy val submit         = phxAttr("submit")
    lazy val autoRecover    = phxAttr("auto-recover")
    lazy val feedbackFor    = phxAttr("feedback-for")
    lazy val triggerAction  = phxAttrBool("trigger-action")
    lazy val disableWith    = phxAttr("disable-with")
    lazy val connected      = phxAttr("connected")
    lazy val disconnected   = phxAttr("disconnected")
    lazy val mounted        = phxAttr("mounted")
    lazy val remove         = phxAttr("remove")
    lazy val update         = new HtmlAttr[PhxUpdate]("phx-update", Encoder(_.value))
    lazy val hook           = phxAttr("hook")
    lazy val dropTarget     = new HtmlAttr[UploadRef]("phx-drop-target", Encoder(_.value))

    def target[Msg](ref: ComponentRef[Msg]): Mod.Attr[Nothing] =
      phxAttr("target") := ref.toString
    def target(selector: DomSelector): Mod.Attr[Nothing] =
      phxAttr("target") := selector.requiredValue

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

  object on:
    private def binding(suffix: String): HtmlAttrBinding =
      new HtmlAttrBinding(s"phx-$suffix")
    private def keyBinding(suffix: String): KeyHtmlAttrBinding =
      new KeyHtmlAttrBinding(s"phx-$suffix")

    lazy val click     = binding("click")
    lazy val clickAway = binding("click-away")

    lazy val blur        = binding("blur")
    lazy val focus       = binding("focus")
    lazy val windowBlur  = binding("window-blur")
    lazy val windowFocus = binding("window-focus")

    lazy val keyDown       = keyBinding("keydown")
    lazy val keyUp         = keyBinding("keyup")
    lazy val windowKeyDown = keyBinding("window-keydown")
    lazy val windowKeyUp   = keyBinding("window-keyup")

    lazy val viewportTop    = binding("viewport-top")
    lazy val viewportBottom = binding("viewport-bottom")

    lazy val change = binding("change")
    lazy val submit = binding("submit")

    private[scalive] lazy val recover        = binding("auto-recover")
    private[scalive] lazy val uploadProgress = binding("progress")

  object connection:
    lazy val onConnect                                  = new HtmlAttrBinding("phx-connected")
    lazy val onDisconnect                               = new HtmlAttrBinding("phx-disconnected")
    lazy val visibleWhenConnected: Vector[Mod[Nothing]] = Vector(
      hidden := true,
      onConnect(JS.removeAttribute("hidden")),
      onDisconnect(JS.setAttribute("hidden" -> ""))
    )
    lazy val visibleWhenDisconnected: Vector[Mod[Nothing]] = Vector(
      hidden := false,
      onConnect(JS.setAttribute("hidden" -> "")),
      onDisconnect(JS.removeAttribute("hidden"))
    )

  object dom:
    lazy val onMount  = new HtmlAttrBinding("phx-mounted")
    lazy val onRemove = new HtmlAttrBinding("phx-remove")

    def hook(name: String, id: DomRef): Vector[Mod.Attr[Nothing]] =
      require(name.nonEmpty, "hook name must not be empty")
      Vector(id.attr, phx.hook := name)

    def ignoreUpdates(id: DomRef): Vector[Mod.Attr[Nothing]] =
      Vector(id.attr, phx.update := PhxUpdate.Ignore)

  object submission:
    lazy val disable: Mod.Attr[Nothing] =
      htmlAttr("phx-disable-with", BooleanAsAttrPresenceEncoder) := true

    def replaceTextWith(text: String): Mod.Attr[Nothing] =
      phx.disableWith := text

  extension [R](upload: LiveUpload[R])
    def dropTarget: Mod.Attr[Nothing] = phx.dropTarget := upload.ref

    def onProgress[Msg](message: Msg): Mod.Attr[Msg] =
      on.uploadProgress(message)

    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] =
      on.uploadProgress(f)

  implicit def stringToMod(v: String): Mod[Nothing]                  = Mod.Content.Text(v)
  implicit def htmlElementToMod[Msg](el: HtmlElement[Msg]): Mod[Msg] = Mod.Content.Tag(el)
end scalive
