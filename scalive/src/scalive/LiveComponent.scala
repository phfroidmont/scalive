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

final case class LiveComponentInstance[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String):
  def render(props: Props): Mod[Nothing] =
    Mod.Content.LiveComponent(LiveComponentSpec(component, id, props))

object LiveComponent:
  trait Eventless[Props, Model] extends LiveComponent[Props, Nothing, Model]:
    final def handleMessage(
      props: Props,
      model: Model,
      ctx: MessageContext
    ): Nothing => LiveIO[Model] =
      _ => ZIO.succeed(model)

  type PropsOf[C] = C match
    case LiveComponent[props, msg, model] => props

final case class ComponentRef[Msg] private[scalive] (private[scalive] val cid: Int):
  override def toString: String = cid.toString

final private[scalive] case class LiveComponentSpec[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String,
  props: Props)

final private[scalive] case class ComponentIdentity(componentClass: Class[?], id: String)

final private[scalive] case class ComponentMessage(cid: Int, message: Any)

sealed private[scalive] trait ComponentRoutedMessage

final private[scalive] case class ComponentTargetMessage(
  componentClass: Class[?],
  message: Any)
    extends ComponentRoutedMessage

final private[scalive] case class ComponentInstanceMessage(
  identity: ComponentIdentity,
  message: Any)
    extends ComponentRoutedMessage

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
