package scalive

import zio.*

/** Defines a stateful child component with typed properties, messages, and local state.
  *
  * A component instance is identified within its owning LiveView by the component's runtime class
  * and its logical ID. Its model is isolated from the parent and from other instances of the same
  * component. The instance is mounted on its first appearance, updated when its properties change,
  * and rendered as part of the parent's tree. Disconnected and connected renders have independent
  * component lifecycles.
  *
  * Event bindings rendered by the component deliver their messages to [[handleMessage]]. Use the
  * [[ComponentRef]] passed to [[view]] when a raw Phoenix event or an event outside the component
  * tree must target this particular instance.
  *
  * A failed lifecycle effect fails the active render or message lifecycle. Rendering the same
  * component class and ID more than once in one tree fails with an `IllegalArgumentException`.
  *
  * @tparam Props
  *   the input properties supplied by the parent
  * @tparam Msg
  *   the messages this component can receive
  * @tparam Model
  *   the state owned by each component instance
  */
trait LiveComponent[Props, Msg, Model]:
  /** Context available while mounting this component. */
  type MountContext = scalive.ComponentMountContext[Props, Msg, Model]

  /** Context available while updating this component's properties. */
  type UpdateContext = scalive.ComponentUpdateContext[Props, Msg, Model]

  /** Context available while handling a message for this component. */
  type MessageContext = scalive.ComponentMessageContext[Props, Msg, Model]

  /** Context available to this component's after-render hooks. */
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  /** Returns the static lifecycle hooks installed for each instance of this component.
    *
    * Hooks are installed before the instance is mounted. Override this method to attach hooks with
    * [[ComponentLiveHooks]]; the default contains no hooks.
    */
  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty

  /** Creates the initial model for a new component instance.
    *
    * This method runs once when the instance first appears in a disconnected or connected
    * lifecycle. The initial [[update]] follows it before the first render.
    *
    * @param props
    *   the properties supplied by the parent
    * @param ctx
    *   the mount-phase capabilities and connection metadata
    * @return
    *   an effect producing the initial component model
    */
  def mount(props: Props, ctx: MountContext): LiveIO[Model]

  /** Applies new properties to an existing component model.
    *
    * This method runs after [[mount]], whenever parent-supplied properties change, and when an
    * explicit component update supplies properties. The default preserves the current model.
    *
    * @param props
    *   the properties to use for the next render
    * @param model
    *   the current component model
    * @param ctx
    *   the update-phase capabilities and connection metadata
    * @return
    *   an effect producing the model to render
    */
  def update(props: Props, model: Model, ctx: UpdateContext): LiveIO[Model] =
    ZIO.succeed(model)

  /** Returns the handler for a message received by this component instance.
    *
    * Messages may originate from the component's rendered event bindings, async operations, or
    * explicitly targeted component bindings. The successful result becomes the component's next
    * model and is rendered unless the lifecycle requests navigation.
    *
    * @param props
    *   the properties currently assigned to the instance
    * @param model
    *   the current component model
    * @param ctx
    *   the message-phase capabilities and connection metadata
    * @return
    *   an effectful handler that produces the next component model
    */
  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => LiveIO[Model]

  /** Constructs this component instance's signal-backed view graph.
    *
    * The runtime invokes this method once for each mounted instance. The `Msg` type restricts event
    * bindings in the returned tree to messages accepted by this component. `self` identifies the
    * current mounted instance and may be passed to targeted event binding helpers or `phx.target`.
    *
    * @param props
    *   the read-only signal containing the properties currently assigned to the instance
    * @param model
    *   the read-only signal containing the current component model
    * @param self
    *   the current instance's typed client target
    */
  def view(
    props: Signal[Props],
    model: Signal[Model],
    self: ComponentRef[Msg]
  ): HtmlElement[Msg]
end LiveComponent

object LiveComponent:
  /** Defines a component with a typed output protocol handled by its immediate owner. */
  trait WithOutput[Props, Msg, Model, Output] extends LiveComponent[Props, Msg, Model]:
    extension (ctx: MessageContext)
      /** Queues `output` for the component's immediate owner. */
      def emit(output: Output): LiveIO[Unit] = ctx.emitOutput(output)

  /** Defines a component that cannot receive component messages. */
  trait Eventless[Props, Model] extends LiveComponent[Props, Nothing, Model]:
    final def handleMessage(
      props: Props,
      model: Model,
      ctx: MessageContext
    ): Nothing => LiveIO[Model] =
      _ => ZIO.succeed(model)

  /** Extracts the property type accepted by a component type. */
  type PropsOf[C] = C match
    case LiveComponent[props, msg, model] => props

/** A stable, typed handle for one logical [[LiveComponent]] instance.
  *
  * Create a handle with `scalive.component`, then reuse it to render the instance, route an event
  * with a binding's `to` method, or send typed property updates. The pair of component runtime
  * class and `id` is the identity; IDs therefore need only be unique among instances of the same
  * component class in one LiveView tree.
  *
  * @param component
  *   the component implementation for this instance
  * @param id
  *   the stable logical ID of this instance
  * @tparam Props
  *   the component's property type
  * @tparam Msg
  *   the component's message type
  * @tparam Model
  *   the component's model type
  */
final case class LiveComponentInstance[Props, Msg, Model](
  component: LiveComponent[Props, Msg, Model],
  id: String):
  /** Renders this instance with `props` as content in a parent tree.
    *
    * The returned modifier cannot emit parent messages because component bindings are handled by
    * the component itself.
    *
    * @param props
    *   the properties supplied to this render
    */
  def render(props: Props): Mod[Nothing] =
    Mod.Content.LiveComponent(LiveComponentSpec(component, id, props, None))

  /** Renders this instance with props sampled from the committed parent graph. */
  def render(props: Signal[Props]): Mod[Nothing] =
    Mod.Content.SignalLiveComponent(LiveComponentSignalSpec(component, id, props, None))

final case class LiveComponentOutputInstance[Props, Msg, Model, Output](
  component: LiveComponent.WithOutput[Props, Msg, Model, Output],
  id: String):

  /** Renders this instance and maps its typed outputs into messages accepted by the enclosing
    * LiveView or LiveComponent.
    *
    * Output delivery is queued after the component's current lifecycle turn. The mapper runs while
    * emitting, so a mapper failure fails that component lifecycle rather than a later owner
    * lifecycle.
    */
  def render[OwnerMsg](props: Props, onOutput: Output => OwnerMsg): Mod[OwnerMsg] =
    Mod.Content.LiveComponent(
      LiveComponentSpec(component, id, props, Some(value => onOutput(value.asInstanceOf[Output])))
    )

  /** Renders this instance with signal props and maps its typed outputs to owner messages. */
  def render[OwnerMsg](
    props: Signal[Props],
    onOutput: Output => OwnerMsg
  ): Mod[OwnerMsg] =
    Mod.Content.SignalLiveComponent(
      LiveComponentSignalSpec(
        component,
        id,
        props,
        Some(value => onOutput(value.asInstanceOf[Output]))
      )
    )

/** A typed reference to one mounted [[LiveComponent]] instance.
  *
  * References are created by the runtime and are valid only for the instance whose
  * [[LiveComponent.view view]] call received them. Use a reference with component-targeted event
  * helpers rather than retaining or constructing one.
  *
  * @tparam Msg
  *   the messages accepted by the referenced component
  */
final case class ComponentRef[Msg] private[scalive] (private[scalive] val cid: Int):
  /** Returns the client component ID used by Phoenix's `phx-target` protocol attribute. */
  override def toString: String = cid.toString

final private[scalive] case class LiveComponentSpec[Props, Msg, Model, Output](
  component: LiveComponent[Props, Msg, Model],
  id: String,
  props: Props,
  outputMapper: Option[Any => Any])

final private[scalive] case class LiveComponentSignalSpec[Props, Msg, Model, Output](
  component: LiveComponent[Props, Msg, Model],
  id: String,
  props: Signal[Props],
  outputMapper: Option[Any => Any])

final private[scalive] case class LiveComponentDynamicSpec[Props, Msg, Model, Output](
  component: LiveComponent[Props, Msg, Model],
  id: Signal[String],
  props: Signal[Props],
  outputMapper: Option[Any => Any])

private[scalive] enum ComponentOutputOwner:
  case Root
  case Component(cid: Int)

final private[scalive] case class ComponentOutputMessage(
  owner: ComponentOutputOwner,
  emitter: ComponentIdentity,
  value: Any)

private[scalive] trait ComponentOutputRuntime:
  def emit(output: Any): LiveIO[Unit]
  def scoped(
    owner: ComponentOutputOwner,
    emitter: ComponentIdentity,
    mapper: Any => Any
  ): ComponentOutputRuntime

private[scalive] object ComponentOutputRuntime:
  object Disabled extends ComponentOutputRuntime:
    def emit(output: Any): LiveIO[Unit] = ZIO.unit
    def scoped(
      owner: ComponentOutputOwner,
      emitter: ComponentIdentity,
      mapper: Any => Any
    ): ComponentOutputRuntime = this

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
