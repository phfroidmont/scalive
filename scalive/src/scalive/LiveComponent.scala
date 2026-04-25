package scalive

trait LiveComponent[Props, Msg, Model]:
  def mount(props: Props): LiveIO[LiveComponent.InitContext, Model]

  def update(props: Props, model: Model): LiveIO[LiveComponent.UpdateContext, Model] =
    val _ = props
    model

  def handleMessage(model: Model): Msg => LiveIO[LiveComponent.UpdateContext, Model]

  def render(model: Model, self: ComponentRef[Msg]): HtmlElement[Msg]

object LiveComponent:
  type InitContext   = LiveContext.BaseCapabilities
  type UpdateContext = LiveContext.NavigationCapabilities

final case class ComponentRef[Msg] private[scalive] (cid: Int):
  override def toString: String = cid.toString

final private[scalive] case class LiveComponentSpec[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String,
  props: Props)

final private[scalive] case class ComponentMessage(cid: Int, message: Any)
