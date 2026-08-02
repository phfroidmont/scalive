package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

import scalive.streams.*
import scalive.upload.*

trait LifecycleContext:
  def connected: Boolean
  def staticChanged: Boolean
  def connectParams: Map[String, Json]

trait MountContext[Msg, Model] extends LifecycleContext:
  def nav: MountNavigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def hooks: RootHooks[Msg, Model]

trait MessageContext[Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait ParamsContext[Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def subscriptions: Subscriptions[Msg]
  def client: Client
  def title: Title
  def components: ComponentUpdates
  def hooks: RootHooks[Msg, Model]

trait AfterRenderContext[Msg, Model] extends LifecycleContext:
  def client: Client
  def hooks: RootHooks[Msg, Model]

trait ComponentMountContext[Props, Msg, Model] extends LifecycleContext:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentUpdateContext[Props, Msg, Model] extends LifecycleContext:
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentMessageContext[Props, Msg, Model] extends LifecycleContext:
  def nav: Navigation
  def flash: Flash
  def uploads: Uploads
  def streams: Streams
  def async: Async[Msg]
  def client: Client
  def components: ComponentUpdates
  def hooks: ComponentHooks[Props, Msg, Model]

trait ComponentAfterRenderContext[Props, Msg, Model] extends LifecycleContext:
  def hooks: ComponentHooks[Props, Msg, Model]

trait MountNavigation:
  def pushNavigate(to: LiveLocation): LiveIO[Unit] = pushNavigateUnsafe(to.href)
  def pushNavigateUnsafe(to: String): LiveIO[Unit]

  def replaceNavigate(to: LiveLocation): LiveIO[Unit] = replaceNavigateUnsafe(to.href)
  def replaceNavigateUnsafe(to: String): LiveIO[Unit]

  def redirect(to: LiveLocation): LiveIO[Unit] = redirectUnsafe(to.href)
  def redirectUnsafe(to: String): LiveIO[Unit]

trait Navigation extends MountNavigation:
  def pushPatch(to: LiveLocation): LiveIO[Unit] = pushPatchUnsafe(to.href)
  def pushPatchUnsafe(to: String): LiveIO[Unit]

  def replacePatch(to: LiveLocation): LiveIO[Unit] = replacePatchUnsafe(to.href)
  def replacePatchUnsafe(to: String): LiveIO[Unit]

trait Flash:
  def put(kind: FlashKind, message: String): LiveIO[Unit]
  def clear(kind: FlashKind): LiveIO[Unit]
  def clearAll: LiveIO[Unit]
  def get(kind: FlashKind): LiveIO[Option[String]]
  def snapshot: LiveIO[Map[FlashKind, String]]

trait Uploads:
  def allow[R](definition: LiveUploadDef[R]): LiveIO[LiveUpload[R]]
  def disallow[R](definition: LiveUploadDef[R]): LiveIO[Unit]
  def get[R](definition: LiveUploadDef[R]): LiveIO[Option[LiveUpload[R]]]
  def cancel[R](entry: LiveUploadEntry[R]): LiveIO[LiveUpload[R]]
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(A, LiveUpload[R])]
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(List[A], LiveUpload[R])]

trait Streams:
  def init[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last,
    reset: Boolean = false,
    limit: Option[StreamLimit] = None
  ): LiveIO[LiveStream[A]]

  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt = StreamAt.Last,
    limit: Option[StreamLimit] = None,
    updateOnly: Boolean = false
  ): LiveIO[LiveStream[A]]

  def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]]
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]]

trait Async[Msg]:
  def start[A](
    key: AsyncKey[A]
  )(
    task: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): LiveIO[Unit]
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): LiveIO[Unit]

trait Subscriptions[Msg]:
  def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]
  def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit]
  def cancel(key: SubscriptionKey): LiveIO[Unit]

trait Client:
  def push[A: JsonEncoder](event: ClientEvent[A], payload: A): LiveIO[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit]

trait Title:
  def set(value: String): LiveIO[Unit]

trait ComponentUpdates:
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): LiveIO[Unit]

trait RootHooks[Msg, Model]:
  def rawEvent: RootRawEventHooks[Msg, Model]
  def event: RootEventHooks[Msg, Model]
  def params: RootParamsHooks[Msg, Model]
  def info: RootInfoHooks[Msg, Model]
  def async: RootAsyncHooks[Msg, Model]
  def afterRender: RootAfterRenderHooks[Msg, Model]

trait RootRawEventHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootEventHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootParamsHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootInfoHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootAsyncHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait RootAfterRenderHooks[Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentHooks[Props, Msg, Model]:
  def rawEvent: ComponentRawEventHooks[Props, Msg, Model]
  def event: ComponentEventHooks[Props, Msg, Model]
  def async: ComponentAsyncHooks[Props, Msg, Model]
  def afterRender: ComponentAfterRenderHooks[Props, Msg, Model]

trait ComponentRawEventHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentEventHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentAsyncHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

trait ComponentAfterRenderHooks[Props, Msg, Model]:
  def attach(
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
  ): LiveIO[Unit]
  def detach(id: String): LiveIO[Unit]

final private[scalive] case class LiveContext(
  staticChanged: Boolean,
  connected: Boolean = false,
  connectParams: Map[String, Json] = Map.empty,
  csrfToken: Option[String] = None,
  uploads: UploadRuntime = UploadRuntime.Disabled,
  streams: StreamRuntime = StreamRuntime.Disabled,
  clientEvents: ClientEventRuntime = ClientEventRuntime.Disabled,
  navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled,
  title: TitleRuntime = TitleRuntime.Disabled,
  components: ComponentUpdateRuntime = ComponentUpdateRuntime.Disabled,
  nestedLiveViews: NestedLiveViewRuntime = NestedLiveViewRuntime.Disabled,
  flash: FlashRuntime = FlashRuntime.Disabled,
  async: LiveAsyncRuntime = LiveAsyncRuntime.Disabled,
  subscriptions: SubscriptionRuntime[Any] = SubscriptionRuntime.Disabled,
  hooks: LiveHookRuntime = LiveHookRuntime.Disabled):

  def mountContext[Msg, Model]: MountContext[Msg, Model] =
    new LiveContext.RuntimeMountContext(this)

  def messageContext[Msg, Model]: MessageContext[Msg, Model] =
    new LiveContext.RuntimeMessageContext(this)

  def paramsContext[Msg, Model]: ParamsContext[Msg, Model] =
    new LiveContext.RuntimeParamsContext(this)

  def afterRenderContext[Msg, Model]: AfterRenderContext[Msg, Model] =
    new LiveContext.RuntimeAfterRenderContext(this)

  def componentMountContext[Props, Msg, Model]: ComponentMountContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentMountContext(this)

  def componentUpdateContext[Props, Msg, Model]: ComponentUpdateContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentUpdateContext(this)

  def componentMessageContext[Props, Msg, Model]: ComponentMessageContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentMessageContext(this)

  def componentAfterRenderContext[Props, Msg, Model]
    : ComponentAfterRenderContext[Props, Msg, Model] =
    new LiveContext.RuntimeComponentAfterRenderContext(this)
end LiveContext

private[scalive] object LiveContext:
  private class RuntimeMountNavigation(runtime: LiveContext) extends MountNavigation:
    def pushNavigateUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.PushNavigate(to))

    def replaceNavigateUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.ReplaceNavigate(to))

    def redirectUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.Redirect(to))

  final private class RuntimeNavigation(runtime: LiveContext)
      extends RuntimeMountNavigation(runtime)
      with Navigation:
    def pushPatchUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.PushPatch(to))

    def replacePatchUnsafe(to: String): LiveIO[Unit] =
      runtime.navigation.request(LiveNavigationCommand.ReplacePatch(to))

  final private class RuntimeFlash(runtime: LiveContext) extends Flash:
    def put(kind: FlashKind, message: String): LiveIO[Unit] =
      runtime.flash.put(kind.value, message)
    def clear(kind: FlashKind): LiveIO[Unit]         = runtime.flash.clear(kind.value)
    def clearAll: LiveIO[Unit]                       = runtime.flash.clearAll
    def get(kind: FlashKind): LiveIO[Option[String]] = runtime.flash.get(kind.value)
    def snapshot: LiveIO[Map[FlashKind, String]]     =
      runtime.flash.snapshot.map(_.map { case (kind, message) => FlashKind(kind) -> message })

  final private class RuntimeUploads(runtime: LiveContext) extends Uploads:
    def allow[R](definition: LiveUploadDef[R]): LiveIO[LiveUpload[R]] =
      runtime.uploads.allow(definition)
    def disallow[R](definition: LiveUploadDef[R]): LiveIO[Unit] =
      runtime.uploads.disallow(definition)
    def get[R](definition: LiveUploadDef[R]): LiveIO[Option[LiveUpload[R]]] =
      runtime.uploads.get(definition)
    def cancel[R](entry: LiveUploadEntry[R]): LiveIO[LiveUpload[R]] =
      runtime.uploads.cancel(entry)
    def consume[R, A](
      entry: LiveUploadEntry[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): LiveIO[(A, LiveUpload[R])] = runtime.uploads.consume(entry)(callback)
    def consumeCompleted[R, A](
      definition: LiveUploadDef[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): LiveIO[(List[A], LiveUpload[R])] = runtime.uploads.consumeCompleted(definition)(callback)

  final private class RuntimeStreams(runtime: LiveContext) extends Streams:
    def init[A](
      definition: LiveStreamDef[A],
      items: Iterable[A],
      at: StreamAt,
      reset: Boolean,
      limit: Option[StreamLimit]
    ): LiveIO[LiveStream[A]] =
      runtime.streams.stream(definition, items, at, reset, limit)

    def insert[A](
      definition: LiveStreamDef[A],
      item: A,
      at: StreamAt,
      limit: Option[StreamLimit],
      updateOnly: Boolean
    ): LiveIO[LiveStream[A]] =
      runtime.streams.insert(definition, item, at, limit, updateOnly)

    def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]] =
      runtime.streams.delete(definition, item)

    def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]] =
      runtime.streams.deleteByDomId(definition, domId)

  final private class RuntimeAsync[Msg](runtime: LiveContext) extends Async[Msg]:
    def start[A](
      key: AsyncKey[A]
    )(
      task: Task[A]
    )(
      toMsg: LiveAsyncResult[A] => Msg
    ): LiveIO[Unit] =
      runtime.async.start(key.value)(task)(toMsg)

    def cancel[A](key: AsyncKey[A], reason: Option[String]): LiveIO[Unit] =
      runtime.async.cancel(key.value, reason)

  final private class RuntimeSubscriptions[Msg](runtime: LiveContext) extends Subscriptions[Msg]:
    private def subscriptions = runtime.subscriptions.asInstanceOf[SubscriptionRuntime[Msg]]

    def start(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
      subscriptions.start(key.value)(stream)

    def replace(key: SubscriptionKey)(stream: zio.stream.ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
      subscriptions.replace(key.value)(stream)

    def cancel(key: SubscriptionKey): LiveIO[Unit] =
      subscriptions.cancel(key.value)

  final private case class PushJsPayload(cmd: String) derives JsonEncoder
  private val PushJsEvent = ClientEvent[PushJsPayload]("js:exec")

  final private class RuntimeClient(runtime: LiveContext) extends Client:
    def push[A: JsonEncoder](event: ClientEvent[A], payload: A): LiveIO[Unit] =
      payload.toJsonAST match
        case Right(encoded) => runtime.clientEvents.push(event.value, encoded)
        case Left(error)    =>
          ZIO.fail(
            new IllegalArgumentException(
              s"Could not encode client event '${event.value}': $error"
            )
          )

    def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit] =
      import JSCommands.JSCommand.given
      push(PushJsEvent, PushJsPayload(js.toJson))

  final private class RuntimeTitle(runtime: LiveContext) extends Title:
    def set(value: String): LiveIO[Unit] = runtime.title.set(value)

  final private class RuntimeComponents(runtime: LiveContext) extends ComponentUpdates:
    def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
      id: String,
      props: LiveComponent.PropsOf[C]
    ): LiveIO[Unit] =
      runtime.components.sendUpdate(summon[ClassTag[C]].runtimeClass, id, props)

  private[scalive] trait RuntimeContextBase extends LifecycleContext:
    protected def runtime: LiveContext
    def connected: Boolean               = runtime.connected
    def staticChanged: Boolean           = runtime.staticChanged
    def connectParams: Map[String, Json] = runtime.connectParams

  final private[scalive] class RuntimeMountContext[Msg, Model](protected val runtime: LiveContext)
      extends MountContext[Msg, Model]
      with RuntimeContextBase:
    val nav: MountNavigation              = RuntimeMountNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val title: Title                      = RuntimeTitle(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeMessageContext[Msg, Model](protected val runtime: LiveContext)
      extends MessageContext[Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                   = RuntimeNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val title: Title                      = RuntimeTitle(runtime)
    val components: ComponentUpdates      = RuntimeComponents(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeParamsContext[Msg, Model](protected val runtime: LiveContext)
      extends ParamsContext[Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                   = RuntimeNavigation(runtime)
    val flash: Flash                      = RuntimeFlash(runtime)
    val uploads: Uploads                  = RuntimeUploads(runtime)
    val streams: Streams                  = RuntimeStreams(runtime)
    val async: Async[Msg]                 = RuntimeAsync(runtime)
    val subscriptions: Subscriptions[Msg] = RuntimeSubscriptions(runtime)
    val client: Client                    = RuntimeClient(runtime)
    val title: Title                      = RuntimeTitle(runtime)
    val components: ComponentUpdates      = RuntimeComponents(runtime)
    val hooks: RootHooks[Msg, Model]      = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeAfterRenderContext[Msg, Model](
    protected val runtime: LiveContext)
      extends AfterRenderContext[Msg, Model]
      with RuntimeContextBase:
    val client: Client               = RuntimeClient(runtime)
    val hooks: RootHooks[Msg, Model] = RuntimeRootHooks(runtime)

  final private[scalive] class RuntimeComponentMountContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentMountContext[Props, Msg, Model]
      with RuntimeContextBase:
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentUpdateContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentUpdateContext[Props, Msg, Model]
      with RuntimeContextBase:
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentMessageContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentMessageContext[Props, Msg, Model]
      with RuntimeContextBase:
    val nav: Navigation                          = RuntimeNavigation(runtime)
    val flash: Flash                             = RuntimeFlash(runtime)
    val uploads: Uploads                         = RuntimeUploads(runtime)
    val streams: Streams                         = RuntimeStreams(runtime)
    val async: Async[Msg]                        = RuntimeAsync(runtime)
    val client: Client                           = RuntimeClient(runtime)
    val components: ComponentUpdates             = RuntimeComponents(runtime)
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private[scalive] class RuntimeComponentAfterRenderContext[Props, Msg, Model](
    protected val runtime: LiveContext)
      extends ComponentAfterRenderContext[Props, Msg, Model]
      with RuntimeContextBase:
    val hooks: ComponentHooks[Props, Msg, Model] = RuntimeComponentHooks(runtime)

  final private class RuntimeRootHooks[Msg, Model](runtime: LiveContext)
      extends RootHooks[Msg, Model]:
    val rawEvent: RootRawEventHooks[Msg, Model]       = RuntimeRootRawEventHooks(runtime)
    val event: RootEventHooks[Msg, Model]             = RuntimeRootEventHooks(runtime)
    val params: RootParamsHooks[Msg, Model]           = RuntimeRootParamsHooks(runtime)
    val info: RootInfoHooks[Msg, Model]               = RuntimeRootInfoHooks(runtime)
    val async: RootAsyncHooks[Msg, Model]             = RuntimeRootAsyncHooks(runtime)
    val afterRender: RootAfterRenderHooks[Msg, Model] = RuntimeRootAfterRenderHooks(runtime)

  final private class RuntimeRootRawEventHooks[Msg, Model](runtime: LiveContext)
      extends RootRawEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachRawEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachRawEvent(id)

  final private class RuntimeRootEventHooks[Msg, Model](runtime: LiveContext)
      extends RootEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachEvent(id)

  final private class RuntimeRootParamsHooks[Msg, Model](runtime: LiveContext)
      extends RootParamsHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachParams(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachParams(id)

  final private class RuntimeRootInfoHooks[Msg, Model](runtime: LiveContext)
      extends RootInfoHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = runtime.hooks.attachInfo(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachInfo(id)

  final private class RuntimeRootAsyncHooks[Msg, Model](runtime: LiveContext)
      extends RootAsyncHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
        LiveHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachAsync(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAsync(id)

  final private class RuntimeRootAfterRenderHooks[Msg, Model](runtime: LiveContext)
      extends RootAfterRenderHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
    ): LiveIO[Unit] = runtime.hooks.attachAfterRender(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAfterRender(id)

  final private class RuntimeComponentHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentHooks[Props, Msg, Model]:
    val rawEvent: ComponentRawEventHooks[Props, Msg, Model] =
      RuntimeComponentRawEventHooks(runtime)
    val event: ComponentEventHooks[Props, Msg, Model] = RuntimeComponentEventHooks(runtime)
    val async: ComponentAsyncHooks[Props, Msg, Model] = RuntimeComponentAsyncHooks(runtime)
    val afterRender: ComponentAfterRenderHooks[Props, Msg, Model] =
      RuntimeComponentAfterRenderHooks(runtime)

  final private class RuntimeComponentRawEventHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentRawEventHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentRawEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachRawEvent(id)

  final private class RuntimeComponentEventHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentEventHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
        LiveEventHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentEvent(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachEvent(id)

  final private class RuntimeComponentAsyncHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentAsyncHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (
        Props,
        Model,
        LiveAsyncEvent[Msg],
        ComponentMessageContext[Props, Msg, Model]
      ) => LiveIO[
        LiveHookResult[Model]
      ]
    ): LiveIO[Unit] = runtime.hooks.attachComponentAsync(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAsync(id)

  final private class RuntimeComponentAfterRenderHooks[Props, Msg, Model](runtime: LiveContext)
      extends ComponentAfterRenderHooks[Props, Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
    ): LiveIO[Unit] = runtime.hooks.attachComponentAfterRender(id)(hook)
    def detach(id: String): LiveIO[Unit] = runtime.hooks.detachAfterRender(id)
end LiveContext
