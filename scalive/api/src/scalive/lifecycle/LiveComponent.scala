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

  /** The events that browser JavaScript pushes to this instance, each with the message it becomes.
    * Defaults to none.
    *
    * A hook's `this.pushEventTo(target, name, payload)`, with a `target` inside this instance,
    * reaches [[handleMessage]] as the message declared for `name`, as in
    * `BrowserEvents.on(CopyResultEvent)(Msg.Copied(_))`. Events pushed with `pushEvent` reach the
    * LiveView instead.
    */
  def browserEvents: BrowserEvents[Msg] = BrowserEvents.none

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
    * A failed effect crashes the owning LiveView's lifecycle, so recover expected failures into the
    * model.
    */
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => Task[Model]

  /** Describes the component's HTML, like [[LiveView.view]].
    *
    * Its bindings, such as `on.click(Msg.Vote)`, send their messages to this instance.
    */
  def view(props: Signal[Props], model: Signal[Model]): HtmlElement[Msg]
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
      * Its hooks, such as a raw event hook, can still emit outputs.
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
  * Render it in the owner's view with `render`, send it a message from a binding rendered elsewhere
  * with `on.click.to(instance)(msg)`, or send it new props with `ctx.components.sendUpdate`.
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

/** Component instances whose ID follows a signal. */
object LiveComponentInstance:
  /** An instance of a stateful component whose ID follows a signal, as returned by
    * `component(definition, id)` with a `Signal[String]` ID.
    *
    * A new ID renders a different instance, with its own model. It can only be rendered: to send it
    * a message or new props, build an instance with its current ID, as in
    * `component(definition, currentId)`.
    *
    * @param component
    *   The component definition.
    * @param id
    *   The instance's logical ID, unique among instances of the same component class.
    */
  final case class Dynamic[Props, Msg, Model](
    component: LiveComponent[Props, Msg, Model],
    id: Signal[String]):
    /** Renders the instance with the current ID at this place in the owner's view, with `props`.
      *
      * This form passes a constant `props` value.
      */
    def render(props: Props): Mod[Nothing] = render(id.map(_ => props))

    /** Renders the instance with the current ID at this place in the owner's view, with `props`.
      *
      * This form passes props that follow a [[Signal]]; the instance receives each changed value in
      * [[LiveComponent.update]].
      */
    def render(props: Signal[Props]): Mod[Nothing] =
      Mod.Content.Component(ComponentSpec.Dynamic(component, id, props))

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

/** Instances of components that report outputs, whose ID follows a signal. */
object LiveComponentOutputInstance:
  /** An instance of a component that reports outputs, whose ID follows a signal, as returned by
    * `component(definition, id)` with a `Signal[String]` ID.
    *
    * A new ID renders a different instance, with its own model. It can only be rendered: to send it
    * a message or new props, build an instance with its current ID, as in
    * `component(definition, currentId)`.
    *
    * @param component
    *   The component definition.
    * @param id
    *   The instance's logical ID, unique among instances of the same component class.
    */
  final case class Dynamic[Props, Msg, Model, Output](
    component: LiveComponent.WithOutput[Props, Msg, Model, Output],
    id: Signal[String]):
    /** Renders the instance with the current ID at this place in the owner's view, with `props`,
      * and turns each of its outputs into an owner message with `onOutput`.
      *
      * This form passes a constant `props` value.
      */
    def render[OwnerMsg](props: Props, onOutput: Output => OwnerMsg): Mod[OwnerMsg] =
      render(id.map(_ => props), onOutput)

    /** Renders the instance with the current ID at this place in the owner's view, with `props`,
      * and turns each of its outputs into an owner message with `onOutput`.
      *
      * This form passes props that follow a [[Signal]]; the instance receives each changed value in
      * [[LiveComponent.update]].
      */
    def render[OwnerMsg](
      props: Signal[Props],
      onOutput: Output => OwnerMsg
    ): Mod[OwnerMsg] =
      Mod.Content.Component(ComponentSpec.OutputDynamic(component, id, props, onOutput))
end LiveComponentOutputInstance

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

/** The runtime identity of one mounted component instance. */
private[scalive] opaque type ComponentRef[+Msg] = ComponentTarget

private[scalive] object ComponentRef:
  private[scalive] def runtime[Msg](identity: Object): ComponentRef[Msg] =
    ComponentTarget(identity)
