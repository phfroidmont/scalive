package scalive

import zio.Task

/** Capabilities shared by the contexts of lifecycle stages that run in both connection phases. */
trait LifecycleContext[+Connected]:
  /** Whether this stage runs for the disconnected HTTP render or a connected lifecycle.
    *
    * Match it to reach capabilities that exist only once the browser has connected, such as async
    * work and subscriptions:
    *
    * {{{
    * ctx.connection match
    *   case Connection.Disconnected =>
    *     ZIO.succeed(Model(reading = AsyncValue.loading))
    *   case Connection.Connected(connected) =>
    *     connected.async
    *       .start(Reading)(thermostat.temperature)(Msg.Measured(_))
    *       .as(Model(reading = AsyncValue.loading))
    * }}}
    *
    * Use `ctx.connection.isConnected` when only the phase matters.
    */
  def connection: Connection[Connected]

/** Capabilities available while a [[LiveView]] mounts.
  *
  * Mount runs for the disconnected HTTP render and again for each connected mount. Connected-only
  * capabilities are reached through [[connection]]. See [the
  * lifecycle](https://scalive.dev/learn/lifecycle-and-connection-behavior).
  */
trait MountContext[Msg, Model] extends LifecycleContext[RootMountConnected[Msg]]:
  /** Redirects or navigates away instead of rendering this page. */
  def nav: MountNavigation

  /** Reads and writes the page's flash messages. */
  def flash: Flash

  /** Allows file uploads that the view renders. */
  def uploads: Uploads

  /** Creates rendered streams, collections updated by item instead of by full replacement. */
  def streams: Streams

  /** Installs lifecycle hooks for this lifecycle only. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a [[LiveView]] handles a message.
  *
  * Messages are handled only in a connected lifecycle, so every capability is available directly.
  * Effects such as navigation, flash, async work, and subscriptions take effect when the handler's
  * model commits; they are discarded if the handler or the render fails. See [the
  * lifecycle](https://scalive.dev/learn/lifecycle-and-connection-behavior#understand-failure-and-commit-boundaries).
  */
trait MessageContext[Msg, Model] extends ConnectedMetadata:
  /** Patches the URL, navigates to another LiveView, or redirects. */
  def nav: Navigation

  /** Reads and writes the page's flash messages. */
  def flash: Flash

  /** Allows, cancels, and consumes file uploads. */
  def uploads: Uploads

  /** Inserts, deletes, and resets items of rendered streams. */
  def streams: Streams

  /** Starts and cancels finite background tasks whose results return as messages. */
  def async: Async[Msg]

  /** Starts, replaces, and cancels message streams owned by this lifecycle. */
  def subscriptions: Subscriptions[Msg]

  /** Pushes events and JavaScript commands to the browser. */
  def client: Client

  /** Sends new props to stateful components rendered by this view. */
  def components: ComponentUpdates

  /** Installs or removes lifecycle hooks for this lifecycle only. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a [[LiveView.Routed LiveView.Routed]] handles new route parameters.
  */
trait ParamsContext[Msg, Model] extends LifecycleContext[RootParamsConnected[Msg]]:
  /** Patches the URL, navigates to another LiveView, or redirects. */
  def nav: Navigation

  /** Reads and writes the page's flash messages. */
  def flash: Flash

  /** Allows, cancels, and consumes file uploads. */
  def uploads: Uploads

  /** Inserts, deletes, and resets items of rendered streams. */
  def streams: Streams

  /** Installs or removes lifecycle hooks for this lifecycle only. */
  def hooks: RootHooks[Msg, Model]

trait AfterRenderContext[Msg, Model] extends LifecycleContext[RootAfterRenderConnected]:
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a [[LiveComponent]] mounts. */
trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while a [[LiveComponent]] receives new props. */
trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while a [[LiveComponent]] handles a message.
  *
  * Async work, subscriptions, uploads, and streams belong to this component instance. Flash and
  * navigation act on the LiveView that renders it.
  */
trait ComponentMessageContext[Props, Msg, Model] extends ConnectedMetadata:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: ComponentHooks[Props, Msg, Model]
  private[scalive] def emit[Output](
    channel: ComponentOutputChannel[Output],
    output: Output
  ): Task[Unit]

trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext[ConnectedMetadata]:
  def hooks: ComponentHooks[Props, Msg, Model]
