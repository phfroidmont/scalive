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

/** Capabilities available while a [[LiveView.Routed LiveView.Routed]] handles a message.
  *
  * Like [[MessageContext]], with navigation that can also patch by the view's parameters, as in
  * `ctx.nav.pushPatch(SearchParams(query))`.
  */
trait RoutedMessageContext[Msg, Model, Params] extends MessageContext[Msg, Model]:
  /** Patches the URL by parameters or location, navigates to another LiveView, or redirects. */
  def nav: RoutedNavigation[Params]

/** Capabilities available while a [[LiveView.Routed LiveView.Routed]] handles new parameters.
  *
  * Like [[ParamsContext]], with navigation that can also patch by the view's parameters, for
  * example to replace an invalid URL with a canonical one.
  */
trait RoutedParamsContext[Msg, Model, Params] extends ParamsContext[Msg, Model]:
  /** Patches the URL by parameters or location, navigates to another LiveView, or redirects. */
  def nav: RoutedNavigation[Params]

private[scalive] object RoutedContexts:
  def message[Msg, Model, Params](
    underlying: MessageContext[Msg, Model],
    location: Params => Either[LiveLocation.EncodeError, LiveLocation]
  ): RoutedMessageContext[Msg, Model, Params] =
    new RoutedMessageContext[Msg, Model, Params]:
      val nav                                           = RoutedNavigation(underlying.nav, location)
      def flash: Flash                                  = underlying.flash
      def uploads: Uploads                              = underlying.uploads
      def streams: Streams                              = underlying.streams
      def async: Async[Msg]                             = underlying.async
      def subscriptions: Subscriptions[Msg]             = underlying.subscriptions
      def client: Client                                = underlying.client
      def components: ComponentUpdates                  = underlying.components
      def hooks: RootHooks[Msg, Model]                  = underlying.hooks
      def staticChanged: Boolean                        = underlying.staticChanged
      def connectParams: Map[String, zio.json.ast.Json] = underlying.connectParams

  def params[Msg, Model, Params](
    underlying: ParamsContext[Msg, Model],
    location: Params => Either[LiveLocation.EncodeError, LiveLocation]
  ): RoutedParamsContext[Msg, Model, Params] =
    new RoutedParamsContext[Msg, Model, Params]:
      val nav                          = RoutedNavigation(underlying.nav, location)
      def flash: Flash                 = underlying.flash
      def uploads: Uploads             = underlying.uploads
      def streams: Streams             = underlying.streams
      def hooks: RootHooks[Msg, Model] = underlying.hooks
      def connection: Connection[RootParamsConnected[Msg]] = underlying.connection

/** Capabilities available to the after-render hooks of a [[LiveView]].
  *
  * After-render hooks run after the disconnected HTTP render and after each connected render. The
  * browser client is reached through [[connection]] once connected.
  */
trait AfterRenderContext[Msg, Model] extends LifecycleContext[RootAfterRenderConnected]:
  /** Installs or removes lifecycle hooks for this lifecycle only. */
  def hooks: RootHooks[Msg, Model]

/** Capabilities available while a [[LiveComponent]] mounts. */
trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  /** Reads and writes the flash messages of the LiveView that renders this component. */
  def flash: Flash

  /** Allows file uploads owned by this component instance. */
  def uploads: Uploads

  /** Creates rendered streams owned by this component instance. */
  def streams: Streams

  /** Installs lifecycle hooks for this component instance only. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while a [[LiveComponent]] receives new props. */
trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  /** Reads and writes the flash messages of the LiveView that renders this component. */
  def flash: Flash

  /** Allows, cancels, and consumes file uploads owned by this component instance. */
  def uploads: Uploads

  /** Inserts, deletes, and resets items of rendered streams owned by this component instance. */
  def streams: Streams

  /** Installs or removes lifecycle hooks for this component instance only. */
  def hooks: ComponentHooks[Props, Msg, Model]

/** Capabilities available while a [[LiveComponent]] handles a message.
  *
  * Async work, subscriptions, uploads, and streams belong to this component instance. Flash and
  * navigation act on the LiveView that renders it.
  */
trait ComponentMessageContext[Props, Msg, Model] extends ConnectedMetadata:
  /** Patches the URL, navigates to another LiveView, or redirects, acting on the LiveView that
    * renders this component.
    */
  def nav: Navigation

  /** Reads and writes the flash messages of the LiveView that renders this component. */
  def flash: Flash

  /** Allows, cancels, and consumes file uploads owned by this component instance. */
  def uploads: Uploads

  /** Inserts, deletes, and resets items of rendered streams owned by this component instance. */
  def streams: Streams

  /** Starts and cancels finite background tasks owned by this component instance, whose results
    * return as messages.
    */
  def async: Async[Msg]

  /** Starts, replaces, and cancels message streams owned by this component instance. */
  def subscriptions: Subscriptions[Msg]

  /** Pushes events and JavaScript commands to the browser. */
  def client: Client

  /** Sends new props to stateful components mounted in the LiveView that renders this component. */
  def components: ComponentUpdates

  /** Installs or removes lifecycle hooks for this component instance only. */
  def hooks: ComponentHooks[Props, Msg, Model]
  private[scalive] def emit[Output](
    channel: ComponentOutputChannel[Output],
    output: Output
  ): Task[Unit]
end ComponentMessageContext

/** Capabilities available to the after-render hooks of a [[LiveComponent]].
  *
  * It exposes connection metadata, reached through [[connection]], and this instance's hooks, but
  * no client operations, navigation, async work, uploads, or streams. Use mount, update, or message
  * handling for those.
  */
trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext[ConnectedMetadata]:
  /** Installs or removes lifecycle hooks for this component instance only. */
  def hooks: ComponentHooks[Props, Msg, Model]
