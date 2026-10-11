package scalive

import zio.{Task, ZIO}

/** A reusable piece of a page with its own model and messages, rendered inside a [[LiveView]].
  *
  * The owner renders it with `component(MyComponent, "id").render(props)`. Each stable ID is a
  * separate instance with its own model; the instance mounts when it is first rendered and is
  * removed when the owner stops rendering it. Use plain functions for markup that needs no state of
  * its own. See the [components guide](https://scalive.dev/guides/components-and-communication).
  *
  * @tparam Props
  *   values supplied by the owner on each render
  * @tparam Msg
  *   messages handled by this component
  * @tparam Model
  *   state owned by one instance
  */
trait LiveComponent[Props, Msg, Model]:
  /** The capabilities passed to [[mount]]. */
  type MountContext = scalive.ComponentMountContext[Props, Msg, Model]

  /** The capabilities passed to [[update]]. */
  type UpdateContext = scalive.ComponentUpdateContext[Props, Msg, Model]

  /** The capabilities passed to [[handleMessage]]. */
  type MessageContext = scalive.ComponentMessageContext[Props, Msg, Model]

  /** The capabilities passed to after-render hooks. */
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  /** Static lifecycle hooks shared by every instance of this component. Defaults to none. */
  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty

  /** Builds the model of a new instance from its first props.
    *
    * It runs in both the disconnected render and the connected lifecycle; match `ctx.connection` to
    * start async work or subscriptions.
    */
  def mount(props: Props, ctx: MountContext): Task[Model]

  /** Receives new props for a mounted instance and returns its next model.
    *
    * Scalive calls it when the owner renders changed props or calls `ctx.components.sendUpdate`.
    * Defaults to keeping the model; preserve it unless the props deliberately reset the component.
    */
  def update(props: Props, model: Model, ctx: UpdateContext): Task[Model] = ZIO.succeed(model)

  /** Handles a message sent to this instance and returns its next model.
    *
    * Bindings target the component with `phx.target(self)` and `on.click.to(self)(msg)`. A failed
    * effect crashes the owning LiveView's lifecycle, so recover expected failures into the model.
    */
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => Task[Model]

  /** Describes the component's HTML, like [[LiveView.view]].
    *
    * `self` identifies this instance as the target of its own bindings.
    */
  def view(props: Signal[Props], model: Signal[Model], self: ComponentRef[Msg]): HtmlElement[Msg]
end LiveComponent

/** Variants of [[LiveComponent]] and a type helper for addressing components. */
object LiveComponent:
  /** A component that renders no bindings that send it messages. */
  trait Eventless[Props, Model] extends LiveComponent[Props, Nothing, Model]:
    /** Returns a handler that is never called, because an eventless component gets no messages. */
    final def handleMessage(
      props: Props,
      model: Model,
      ctx: MessageContext
    ): Nothing => Task[Model] = _ => ZIO.succeed(model)

  /** A component that reports domain events of type `Output0` to its owner.
    *
    * The owner maps each output to one of its own messages when it renders the component, as in
    * `component(Vote, "scala").render(props, output => Msg.Voted(output))`.
    */
  trait WithOutput[Props, Msg, Model, Output0] extends LiveComponent[Props, Msg, Model]:
    final private[scalive] val outputChannel: ComponentOutputChannel[Output0] =
      ComponentOutputChannel()

    extension (ctx: MessageContext)
      /** Sends `output` to the owner, which handles it after this component's turn renders. */
      final def emit(output: Output0): Task[Unit] = ctx.emit(outputChannel, output)

  /** Variants of [[LiveComponent.WithOutput]]. */
  object WithOutput:
    /** A component that reports outputs to its owner but renders no bindings that send it messages.
      *
      * Its hooks can still emit outputs, for example from an `onBrowserEvent` handler.
      */
    trait Eventless[Props, Model, Output]
        extends LiveComponent.WithOutput[Props, Nothing, Model, Output]:
      /** Returns a handler that is never called, because an eventless component gets no messages.
        */
      final def handleMessage(
        props: Props,
        model: Model,
        ctx: MessageContext
      ): Nothing => Task[Model] = _ => ZIO.succeed(model)
end LiveComponent

/** Protocol-neutral declaration consumed by the HTML renderer. */
sealed private[scalive] trait ComponentSpec[+OwnerMsg]

private[scalive] object ComponentSpec:
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

/** One named instance of a stateful component, as returned by `component(definition, id)`.
  *
  * Render it in the owner's view with `render`, target its bindings with `on.click.to(instance)`,
  * or send it new props with `ctx.components.sendUpdate`.
  *
  * @param component
  *   The component definition.
  * @param id
  *   The instance's logical ID, unique among instances of the same component class. Keep it stable
  *   so the instance keeps its model.
  */
final case class LiveComponentInstance[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String):
  /** Renders this instance at this place in the owner's view, with `props`.
    *
    * This form passes a constant `props` value.
    */
  def render(props: Props): Mod[Nothing] =
    Mod.Content.Component(ComponentSpec.Plain(component, id, props))

  /** Renders this instance at this place in the owner's view, with `props`.
    *
    * This form passes props that follow a [[Signal]]; the instance receives each changed value in
    * [[LiveComponent.update]].
    */
  def render(props: Signal[Props]): Mod[Nothing] =
    Mod.Content.Component(ComponentSpec.PlainSignal(component, id, props))

/** One named instance of a component that reports outputs, as returned by
  * `component(definition, id)`.
  *
  * Rendering it requires a function that turns each output into a message of the owner.
  *
  * @param component
  *   The component definition.
  * @param id
  *   The instance's logical ID, unique among instances of the same component class. Keep it stable
  *   so the instance keeps its model.
  */
final case class LiveComponentOutputInstance[Props, Msg, Model, Output](
  component: LiveComponent.WithOutput[Props, Msg, Model, Output],
  id: String):
  /** Renders this instance at this place in the owner's view, with `props`, and turns each of its
    * outputs into an owner message with `onOutput`.
    *
    * This form passes a constant `props` value.
    */
  def render[OwnerMsg](props: Props, onOutput: Output => OwnerMsg): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.Output(component, id, props, onOutput))

  /** Renders this instance at this place in the owner's view, with `props`, and turns each of its
    * outputs into an owner message with `onOutput`.
    *
    * This form passes props that follow a [[Signal]]; the instance receives each changed value in
    * [[LiveComponent.update]].
    */
  def render[OwnerMsg](
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.Component(ComponentSpec.OutputSignal(component, id, props, onOutput))

/** The link that carries a [[LiveComponent.WithOutput]] component's outputs to its owner.
  *
  * Scalive creates and uses it; components emit outputs with `ctx.emit`.
  */
final class ComponentOutputChannel[Output] private[scalive] ()

/** Constructors for [[ComponentOutputChannel]], reserved for Scalive. */
object ComponentOutputChannel:
  private[scalive] def apply[Output](): ComponentOutputChannel[Output] =
    new ComponentOutputChannel()

final private[scalive] class ComponentTarget(private[scalive] val identity: Object):
  override def toString: String = "ComponentRef"

/** A reference to one mounted component instance, passed to its `view` as `self`.
  *
  * Make the instance the target of its own bindings with `phx.target(self)` and
  * `on.click.to(self)(msg)`. The representation is hidden: do not store a reference in a model or
  * keep it after the instance is removed.
  */
opaque type ComponentRef[Msg] = ComponentTarget

/** Constructors for [[ComponentRef]], reserved for Scalive. */
object ComponentRef:
  private[scalive] def runtime[Msg](identity: Object): ComponentRef[Msg] =
    ComponentTarget(identity)
