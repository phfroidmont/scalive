package scalive

import zio.ZIO

trait LiveComponent[Props, Msg, Model]:
  type MountContext       = scalive.ComponentMountContext[Props, Msg, Model]
  type UpdateContext      = scalive.ComponentUpdateContext[Props, Msg, Model]
  type MessageContext     = scalive.ComponentMessageContext[Props, Msg, Model]
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty
  def mount(props: Props, ctx: MountContext): LiveIO[Model]
  def update(props: Props, model: Model, ctx: UpdateContext): LiveIO[Model] = ZIO.succeed(model)
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => LiveIO[Model]
  def view(props: Signal[Props], model: Signal[Model], self: ComponentRef[Msg]): HtmlElement[Msg]

object LiveComponent:
  trait Eventless[Props, Model] extends LiveComponent[Props, Nothing, Model]:
    final def handleMessage(
      props: Props,
      model: Model,
      ctx: MessageContext
    ): Nothing => LiveIO[Model] = _ => ZIO.succeed(model)

  trait WithOutput[Props, Msg, Model, Output0] extends LiveComponent[Props, Msg, Model]:
    final private[scalive] val outputChannel: ComponentOutputChannel[Output0] =
      ComponentOutputChannel()

    extension (ctx: MessageContext)
      final def emit(output: Output0): LiveIO[Unit] = ctx.emit(outputChannel, output)

  object WithOutput:
    trait Eventless[Props, Model, Output]
        extends LiveComponent.WithOutput[Props, Nothing, Model, Output]:
      final def handleMessage(
        props: Props,
        model: Model,
        ctx: MessageContext
      ): Nothing => LiveIO[Model] = _ => ZIO.succeed(model)

  trait EventlessWithOutput[Props, Model, Output] extends WithOutput.Eventless[Props, Model, Output]

  type PropsOf[C] = C match
    case LiveComponent[props, msg, model] => props

/** Protocol-neutral declaration consumed by the HTML renderer. */
sealed trait ComponentSpec[+OwnerMsg]

object ComponentSpec:
  final case class Plain[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Props)
      extends ComponentSpec[Nothing]

  final case class PlainSignal[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: String,
    props: Signal[Props])
      extends ComponentSpec[Nothing]

  final case class Dynamic[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Signal[String],
    props: Signal[Props])
      extends ComponentSpec[Nothing]

  final case class Output[Props, Msg, Model, Out, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Out],
    id: String,
    props: Props,
    onOutput: Out => OwnerMsg)
      extends ComponentSpec[OwnerMsg]

  final case class OutputSignal[Props, Msg, Model, Out, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Out],
    id: String,
    props: Signal[Props],
    onOutput: Out => OwnerMsg)
      extends ComponentSpec[OwnerMsg]

  final case class OutputDynamic[Props, Msg, Model, Out, OwnerMsg](
    component: LiveComponent.WithOutput[Props, Msg, Model, Out],
    id: Signal[String],
    props: Signal[Props],
    onOutput: Out => OwnerMsg)
      extends ComponentSpec[OwnerMsg]
end ComponentSpec

final case class LiveComponentInstance[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String):
  def render(props: Props): Mod[Nothing] =
    Mod.Content.Component(ComponentSpec.Plain(component, id, props))
  def render(props: Signal[Props]): Mod[Nothing] =
    Mod.Content.Component(ComponentSpec.PlainSignal(component, id, props))

final case class LiveComponentOutputInstance[Props, Msg, Model, Output](
  component: LiveComponent.WithOutput[Props, Msg, Model, Output],
  id: String):
  def render[OwnerMsg](props: Props, onOutput: Output => OwnerMsg): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.Output(component, id, props, onOutput))
  def render[OwnerMsg](
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputSignal(component, id, props, onOutput))

final class ComponentOutputChannel[Output] private[scalive] ()

object ComponentOutputChannel:
  private[scalive] def apply[Output](): ComponentOutputChannel[Output] =
    new ComponentOutputChannel()

final private[scalive] class ComponentTarget(private[scalive] val identity: Object):
  override def toString: String = "ComponentRef"

opaque type ComponentRef[Msg] = ComponentTarget

object ComponentRef:
  private[scalive] def runtime[Msg](identity: Object): ComponentRef[Msg] =
    ComponentTarget(identity)
