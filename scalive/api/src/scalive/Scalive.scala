import scalive.codecs.BooleanAsAttrPresenceEncoder
import scalive.codecs.Encoder
import scalive.codecs.IntAsStringEncoder
import scalive.codecs.StringAsIsEncoder
import scalive.defs.attrs.HtmlAttrs
import scalive.defs.complex.ComplexHtmlKeys
import scalive.defs.components.Components
import scalive.defs.tags.HtmlTags

/** Ergonomic API imported with `scalive.*`. */
package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components:
  export _root_.scalive.streams.api.*
  export _root_.scalive.upload.api.*

  lazy val defer = htmlAttr("defer", BooleanAsAttrPresenceEncoder)

  def rawHtml(html: String): Mod[Nothing]         = Mod.Content.Text(html, raw = true)
  def rawHtml(html: Signal[String]): Mod[Nothing] = Mod.Content.SignalText(html, raw = true)

  def component[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String
  ): LiveComponentInstance[Props, Msg, Model] = LiveComponentInstance(value, id)

  def component[Props, Msg, Model, Output](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String
  ): LiveComponentOutputInstance[Props, Msg, Model, Output] =
    LiveComponentOutputInstance(value, id)

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.Plain(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: String,
    props: Signal[Props]
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.PlainSignal(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Signal[Props]
  ): Mod[Nothing] = Mod.Content.Component(ComponentSpec.Dynamic(value, id, props))

  def liveComponent[Props, Msg, Model](
    value: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Props
  ): Mod[Nothing] = liveComponent(value, id, id.map(_ => props))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Props,
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.Output(value, id, props, onOutput))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: String,
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputSignal(value, id, props, onOutput))

  def liveComponent[Props, Msg, Model, Output, OwnerMsg](
    value: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: Signal[String],
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputDynamic(value, id, props, onOutput))

  def liveView[Msg, Model](
    id: String,
    value: => LiveView[Msg, Model],
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = false
  ): Mod[Nothing] =
    Mod.Content.NestedView(NestedViewSpec.Static(id, () => value, sticky, linkParentOnCrash))

  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A],
    sticky: Boolean,
    linkParentOnCrash: A => Boolean
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod[Nothing] =
    Mod.Content.NestedView(
      NestedViewSpec.Dynamic(id, value, factory, sticky, linkParentOnCrash)
    )

  def liveView[A, Msg, Model](
    id: String,
    value: Signal[A]
  )(
    factory: A => LiveView[Msg, Model]
  ): Mod[Nothing] = liveView(id, value, sticky = false, _ => false)(factory)

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
    val content = mods.toVector.flatMap {
      case mod: Mod[Msg]                  => Some(mod)
      case values: IterableOnce[Mod[Msg]] => values
    }
    val wrapper = Vector.newBuilder[Mod[Msg]]
    wrapper += (idAttr := s"_lv_portal_wrap_$id")
    wrapperClass.foreach(value => wrapper += (cls := value))
    wrapper ++= content

    portalTemplateTag(
      idAttr    := id,
      phxPortal := target.requiredValue,
      HtmlTag(container)(wrapper.result())
    )

  object flash:
    def apply(kind: FlashKind)(project: String => HtmlElement[Nothing]): Mod[Nothing] =
      Mod.Content.Flash(kind, project)

    lazy val clearOnClick: Mod.Attr[Nothing] = phx.click := "lv:clear-flash"

    def clearOnClick(kind: FlashKind): Vector[Mod.Attr[Nothing]] =
      Vector(clearOnClick, phx.value("key") := kind.value)

  def liveTitle(
    pageTitle: Option[String],
    default: String,
    prefix: String = "",
    suffix: String = ""
  ): HtmlElement[Nothing] =
    val title = pageTitle.filter(_.trim.nonEmpty).getOrElse(default)
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      s"$prefix$title$suffix"
    )

  def liveTitle(
    pageTitle: Signal[Option[String]],
    default: String,
    prefix: String,
    suffix: String
  ): HtmlElement[Nothing] =
    titleTag(
      dataAttr("prefix")  := prefix,
      dataAttr("default") := default,
      dataAttr("suffix")  := suffix,
      pageTitle.map(value => s"$prefix${value.filter(_.trim.nonEmpty).getOrElse(default)}$suffix")
    )

  def liveTitle(pageTitle: Signal[Option[String]], default: String): HtmlElement[Nothing] =
    liveTitle(pageTitle, default, "", "")

  object link:
    def pushNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.href, mods*)
    def pushNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      pushNavigateUnsafe(to.map(_.href), mods*)
    def pushNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "redirect", phx.linkState := "push", mods)
    def pushNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "redirect", phx.linkState := "push", mods)
    def replaceNavigate[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.href, mods*)
    def replaceNavigate[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      replaceNavigateUnsafe(to.map(_.href), mods*)
    def replaceNavigateUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "redirect", phx.linkState := "replace", mods)
    def replaceNavigateUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "redirect", phx.linkState := "replace", mods)
    def pushPatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.href, mods*)
    def pushPatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      pushPatchUnsafe(to.map(_.href), mods*)
    def pushPatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "patch", phx.linkState := "push", mods)
    def pushPatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "patch", phx.linkState := "push", mods)
    def replacePatch[Msg](to: LiveLocation, mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.href, mods*)
    def replacePatch[Msg](to: Signal[LiveLocation], mods: Mod[Msg]*): HtmlElement[Msg] =
      replacePatchUnsafe(to.map(_.href), mods*)
    def replacePatchUnsafe[Msg](to: String, mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "patch", phx.linkState := "replace", mods)
    def replacePatchUnsafe[Msg](to: Signal[String], mods: Mod[Msg]*): HtmlElement[Msg] =
      a(href := to, phx.link := "patch", phx.linkState := "replace", mods)
  end link

  object phx:
    private def attr(name: String)     = htmlAttr(s"phx-$name", StringAsIsEncoder)
    private def boolAttr(name: String) = htmlAttr(s"phx-$name", BooleanAsAttrPresenceEncoder)
    private def intAttr(name: String)  = htmlAttr(s"phx-$name", IntAsStringEncoder)
    private def data(name: String)     = dataAttr(s"phx-$name")

    private[scalive] lazy val link      = data("link")
    private[scalive] lazy val linkState = data("link-state")

    lazy val click         = attr("click")
    lazy val clickAway     = attr("click-away")
    lazy val blur          = attr("blur")
    lazy val focus         = attr("focus")
    lazy val windowBlur    = attr("window-blur")
    lazy val windowFocus   = attr("window-focus")
    lazy val keyDown       = attr("keydown")
    lazy val keyUp         = attr("keyup")
    lazy val windowKeyDown = attr("window-keydown")
    lazy val windowKeyUp   = attr("window-keyup")
    lazy val change        = attr("change")
    lazy val submit        = attr("submit")
    lazy val autoRecover   = attr("auto-recover")
    lazy val feedbackFor   = attr("feedback-for")
    lazy val triggerAction = boolAttr("trigger-action")
    lazy val disableWith   = attr("disable-with")
    lazy val connected     = attr("connected")
    lazy val disconnected  = attr("disconnected")
    lazy val mounted       = attr("mounted")
    lazy val remove        = attr("remove")
    lazy val hook          = attr("hook")
    lazy val update        = HtmlAttr[PhxUpdate]("phx-update", Encoder(_.value))
    lazy val dropTarget    = HtmlAttr[UploadRef]("phx-drop-target", Encoder(_.value))
    lazy val debounce      = HtmlAttr["blur" | Int](
      "phx-debounce",
      Encoder {
        case _: "blur"  => "blur"
        case value: Int => value.toString
      }
    )
    lazy val throttle    = intAttr("throttle")
    lazy val trackStatic = boolAttr("track-static")

    def target[Msg](ref: ComponentRef[Msg]): Mod.Attr[Msg] =
      Mod.Attr.ComponentTarget(ref)

    def target(selector: DomSelector): Mod.Attr[Nothing] = attr("target") := selector.requiredValue

    def value(key: String) = attr(s"value-$key")
  end phx

  object on:
    private def binding(name: String)    = HtmlAttrBinding(s"phx-$name")
    private def keyBinding(name: String) = KeyHtmlAttrBinding(s"phx-$name")

    lazy val click                           = binding("click")
    lazy val clickAway                       = binding("click-away")
    lazy val blur                            = binding("blur")
    lazy val focus                           = binding("focus")
    lazy val windowBlur                      = binding("window-blur")
    lazy val windowFocus                     = binding("window-focus")
    lazy val keyDown                         = keyBinding("keydown")
    lazy val keyUp                           = keyBinding("keyup")
    lazy val windowKeyDown                   = keyBinding("window-keydown")
    lazy val windowKeyUp                     = keyBinding("window-keyup")
    lazy val viewportTop                     = binding("viewport-top")
    lazy val viewportBottom                  = binding("viewport-bottom")
    lazy val change                          = binding("change")
    lazy val submit                          = binding("submit")
    private[scalive] lazy val recover        = binding("auto-recover")
    private[scalive] lazy val uploadProgress = binding("progress")

  object connection:
    lazy val onConnect                                  = HtmlAttrBinding("phx-connected")
    lazy val onDisconnect                               = HtmlAttrBinding("phx-disconnected")
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
    lazy val onMount  = HtmlAttrBinding("phx-mounted")
    lazy val onRemove = HtmlAttrBinding("phx-remove")

    def hook(name: String, id: DomRef): Vector[Mod.Attr[Nothing]] =
      require(name.nonEmpty, "hook name must not be empty")
      Vector(id.attr, phx.hook := name)

    def ignoreUpdates(id: DomRef): Vector[Mod.Attr[Nothing]] =
      Vector(id.attr, phx.update := PhxUpdate.Ignore)

  object submission:
    lazy val disable: Mod.Attr[Nothing] =
      htmlAttr("phx-disable-with", BooleanAsAttrPresenceEncoder) := true
    def replaceTextWith(text: String): Mod.Attr[Nothing] = phx.disableWith := text

  extension [R](upload: LiveUpload[R])
    def dropTarget: Mod.Attr[Nothing]                                 = phx.dropTarget := upload.ref
    def onProgress[Msg](message: Msg): Mod.Attr[Msg]                  = on.uploadProgress(message)
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] = on.uploadProgress(f)

  extension [R](upload: Signal[LiveUpload[R]])
    def dropTarget: Mod.Attr[Nothing]                = phx.dropTarget := upload.map(_.ref)
    def onProgress[Msg](message: Msg): Mod.Attr[Msg] = on.uploadProgress(message)
    def onProgress[Msg](f: Map[String, String] => Msg): Mod.Attr[Msg] = on.uploadProgress(f)

  implicit def stringToMod(value: String): Mod[Nothing]               = Mod.Content.Text(value)
  implicit def signalStringToMod(value: Signal[String]): Mod[Nothing] =
    Mod.Content.SignalText(value)
  implicit def htmlElementToMod[Msg](value: HtmlElement[Msg]): Mod[Msg] = Mod.Content.Tag(value)
end scalive
