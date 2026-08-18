package scalive

trait LifecycleContext[+Connected]:
  def connection: Connection[Connected]

trait MountContext[Msg, Model] extends LifecycleContext[RootMountConnected[Msg]]:
  def nav: MountNavigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: RootHooks[Msg, Model]

trait MessageContext[Msg, Model] extends ConnectedMetadata:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait ParamsContext[Msg, Model] extends LifecycleContext[RootParamsConnected[Msg]]:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: RootHooks[Msg, Model]

trait AfterRenderContext[Msg, Model] extends LifecycleContext[RootAfterRenderConnected]:
  def hooks: RootHooks[Msg, Model]

trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext[ComponentConnected[Msg]]:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentMessageContext[Props, Msg, Model] extends ConnectedMetadata:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: ComponentHooks[Props, Msg, Model]
  private[scalive] def emit[Output](
    channel: ComponentOutputChannel[Output],
    output: Output
  ): LiveIO[Unit]

trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext[ConnectedMetadata]:
  def hooks: ComponentHooks[Props, Msg, Model]
