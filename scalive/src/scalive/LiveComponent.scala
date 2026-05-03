package scalive

import zio.*

trait LiveComponent[Props, Msg, Model]:
  type MountContext       = scalive.ComponentMountContext[Props, Msg, Model]
  type UpdateContext      = scalive.ComponentUpdateContext[Props, Msg, Model]
  type MessageContext     = scalive.ComponentMessageContext[Props, Msg, Model]
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty

  def mount(props: Props, ctx: MountContext): LiveIO[Model]

  def update(props: Props, model: Model, ctx: UpdateContext): LiveIO[Model] =
    ZIO.succeed(model)

  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => LiveIO[Model]

  def render(props: Props, model: Model, self: ComponentRef[Msg]): HtmlElement[Msg]

object LiveComponent:
  type PropsOf[C] = C match
    case LiveComponent[props, msg, model] => props

  type MsgOf[C] = C match
    case LiveComponent[props, msg, model] => msg

final case class ComponentRef[Msg] private[scalive] (cid: Int):
  override def toString: String = cid.toString

final private[scalive] case class LiveComponentSpec[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String,
  props: Props)

final private[scalive] case class ComponentIdentity(componentClass: Class[?], id: String)

final private[scalive] case class ComponentMessage(cid: Int, message: Any)

final case class ComponentTargetMessage private[scalive] (
  componentClass: Class[?],
  message: Any)

private[scalive] trait ComponentUpdateRuntime:
  def sendUpdate[Props](
    componentClass: Class[?],
    id: String,
    props: Props
  ): UIO[Unit]

private[scalive] object ComponentUpdateRuntime:
  object Disabled extends ComponentUpdateRuntime:
    def sendUpdate[Props](
      componentClass: Class[?],
      id: String,
      props: Props
    ): UIO[Unit] = ZIO.unit
