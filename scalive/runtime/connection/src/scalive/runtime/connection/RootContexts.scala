package scalive.runtime.connection

import scala.reflect.ClassTag

import zio.Ref
import zio.Task
import zio.UIO
import zio.ZIO
import zio.http.URL
import zio.json.EncoderOps
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.runtime.kernel.*
import scalive.streams.*
import scalive.upload.*

private object Deferred:
  final case class Unsupported(operation: String)
      extends RuntimeException(s"$operation is not available in this root lifecycle")

  def fail[A](operation: String): LiveIO[A] = ZIO.fail(Unsupported(operation))

final private[scalive] class RootTurnJournal private (
  val navigation: Ref[Option[NavigationRequest]],
  val hooks: Ref[RootHookRegistry[Any, Any]],
  val flash: Ref[Map[FlashKind, String]],
  val clientEvents: Ref[Vector[ClientEffect]],
  val componentUpdates: Ref[Vector[ComponentUpdateRequest]]):

  def hookRegistry[Msg, Model]: UIO[RootHookRegistry[Msg, Model]] =
    hooks.get.map(_.asInstanceOf[RootHookRegistry[Msg, Model]])

  def updateHooks[Msg, Model](
    f: RootHookRegistry[Msg, Model] => RootHookRegistry[Msg, Model]
  ): UIO[Unit] = hooks.update(value =>
    f(value.asInstanceOf[RootHookRegistry[Msg, Model]]).asInstanceOf[RootHookRegistry[Any, Any]]
  )

  def navigationWithFlash: UIO[Option[NavigationRequest]] =
    navigation.get.zipWith(flash.get)((request, values) => request.map(_.copy(flash = values)))

private[scalive] object RootTurnJournal:
  def make[Msg, Model](
    registry: RootHookRegistry[Msg, Model],
    initialFlash: Map[FlashKind, String] = Map.empty,
    initialClientEvents: Vector[ClientEffect] = Vector.empty,
    initialComponentUpdates: Vector[ComponentUpdateRequest] = Vector.empty,
    initialNavigation: Option[NavigationRequest] = None
  ): LiveIO[RootTurnJournal] =
    for
      navigation   <- Ref.make(initialNavigation)
      hooks        <- Ref.make(registry.asInstanceOf[RootHookRegistry[Any, Any]])
      flash        <- Ref.make(initialFlash)
      clientEvents <- Ref.make(initialClientEvents)
      updates      <- Ref.make(initialComponentUpdates)
    yield new RootTurnJournal(navigation, hooks, flash, clientEvents, updates)

final private class RootNavigation(
  currentUrl: URL,
  journal: RootTurnJournal,
  allowPatch: Boolean)
    extends Navigation:
  def pushNavigateUnsafe(to: String): LiveIO[Unit] =
    record(to, NavigationKind.PushNavigate)
  def replaceNavigateUnsafe(to: String): LiveIO[Unit] =
    record(to, NavigationKind.ReplaceNavigate)
  def redirectUnsafe(to: String): LiveIO[Unit]  = record(to, NavigationKind.Redirect)
  def pushPatchUnsafe(to: String): LiveIO[Unit] =
    if allowPatch then record(to, NavigationKind.PushPatch)
    else Deferred.fail("push patch")
  def replacePatchUnsafe(to: String): LiveIO[Unit] =
    if allowPatch then record(to, NavigationKind.ReplacePatch)
    else Deferred.fail("replace patch")

  private def record(to: String, kind: NavigationKind): LiveIO[Unit] =
    for
      destination <- ZIO.fromEither(RootNavigation.resolve(currentUrl, to))
      accepted    <- journal.navigation.modify {
                    case None           => true  -> Some(NavigationRequest(destination, kind))
                    case some @ Some(_) => false -> some
                  }
      _ <- ZIO
             .fail(IllegalStateException("a lifecycle turn requested more than one navigation"))
             .unless(accepted)
    yield ()

private object RootNavigation:
  def resolve(current: URL, destination: String): Either[Throwable, URL] =
    val value =
      if destination.startsWith("?") || destination.startsWith("#") then
        s"${current.path.encode}$destination"
      else destination
    URL.decode(value).left.map(error => IllegalArgumentException(error.getMessage))

final private class RootMountNavigation(currentUrl: URL, journal: RootTurnJournal)
    extends MountNavigation:
  private val navigation = new RootNavigation(currentUrl, journal, allowPatch = false)
  def pushNavigateUnsafe(to: String): LiveIO[Unit]    = navigation.pushNavigateUnsafe(to)
  def replaceNavigateUnsafe(to: String): LiveIO[Unit] = navigation.replaceNavigateUnsafe(to)
  def redirectUnsafe(to: String): LiveIO[Unit]        = navigation.redirectUnsafe(to)

private object DeferredFlash extends Flash:
  def put(kind: FlashKind, message: String): LiveIO[Unit] = Deferred.fail("put flash")
  def clear(kind: FlashKind): LiveIO[Unit]                = Deferred.fail("clear flash")
  def clearAll: LiveIO[Unit]                              = Deferred.fail("clear all flash")
  def get(kind: FlashKind): LiveIO[Option[String]]        = Deferred.fail("get flash")
  def snapshot: LiveIO[Map[FlashKind, String]]            = Deferred.fail("snapshot flash")

final private class JournaledFlash(journal: RootTurnJournal) extends Flash:
  def put(kind: FlashKind, message: String): LiveIO[Unit] =
    ZIO.fail(NullPointerException("flash message must not be null")).when(message == null) *>
      journal.flash.update(_.updated(kind, message))
  def clear(kind: FlashKind): LiveIO[Unit]         = journal.flash.update(_ - kind)
  def clearAll: LiveIO[Unit]                       = journal.flash.set(Map.empty)
  def get(kind: FlashKind): LiveIO[Option[String]] = journal.flash.get.map(_.get(kind))
  def snapshot: LiveIO[Map[FlashKind, String]]     = journal.flash.get

private object DeferredUploads extends Uploads:
  def allow[R](definition: LiveUploadDef[R]): LiveIO[LiveUpload[R]] = Deferred.fail("allow upload")
  def disallow[R](definition: LiveUploadDef[R]): LiveIO[Unit] = Deferred.fail("disallow upload")
  def get[R](definition: LiveUploadDef[R]): LiveIO[Option[LiveUpload[R]]] =
    Deferred.fail("get upload")
  def cancel[R](entry: LiveUploadEntry[R]): LiveIO[LiveUpload[R]] = Deferred.fail("cancel upload")
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(A, LiveUpload[R])] = Deferred.fail("consume upload")
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): LiveIO[(List[A], LiveUpload[R])] = Deferred.fail("consume completed uploads")

private object DeferredStreams extends Streams:
  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): LiveIO[LiveStream[A]] =
    Deferred.fail("create stream")
  def insertAll[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt
  ): LiveIO[LiveStream[A]] = Deferred.fail("insert stream items")
  def reset[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt
  ): LiveIO[LiveStream[A]] = Deferred.fail("reset stream")
  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt,
    updateOnly: Boolean
  ): LiveIO[LiveStream[A]] = Deferred.fail("insert stream item")
  def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]] =
    Deferred.fail("delete stream item")
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]] =
    Deferred.fail("delete stream item by DOM id")

final private class DeferredAsync[Msg] extends Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: LiveAsyncResult[A] => Msg): LiveIO[Unit] =
    Deferred.fail("start async task")
  def cancel[A](key: AsyncKey[A], reason: Option[String]): LiveIO[Unit] =
    Deferred.fail("cancel async task")

final private class DeferredSubscriptions[Msg] extends Subscriptions[Msg]:
  def start(key: SubscriptionKey)(stream: ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
    Deferred.fail("start subscription")
  def replace(key: SubscriptionKey)(stream: ZStream[Any, Nothing, Msg]): LiveIO[Unit] =
    Deferred.fail("replace subscription")
  def cancel(key: SubscriptionKey): LiveIO[Unit] = Deferred.fail("cancel subscription")

final private class JournaledClient(journal: RootTurnJournal) extends Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): LiveIO[Unit] =
    payload.toJsonAST match
      case Right(value) => journal.clientEvents.update(_ :+ ClientEffect(event.value, value))
      case Left(error)  =>
        ZIO.fail(
          IllegalArgumentException(s"Could not encode client event '${event.value}': $error")
        )

  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit] =
    import JSCommands.JSCommand.given
    journal.clientEvents.update(
      _ :+ ClientEffect("js:exec", Json.Obj("cmd" -> Json.Str(js.toJson)))
    )

final private class JournaledComponentUpdates(journal: RootTurnJournal) extends ComponentUpdates:
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): LiveIO[Unit] = journal.componentUpdates.update(_ :+ ComponentUpdateRequest(instance, props))
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): LiveIO[Unit] = journal.componentUpdates.update(_ :+ ComponentUpdateRequest(instance, props))
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): LiveIO[Unit] =
    ZIO
      .attempt {
        val component = summon[ClassTag[C]].runtimeClass
          .getField("MODULE$").get(null).asInstanceOf[C]
        ComponentUpdateRequest(
          component.asInstanceOf[LiveComponent[LiveComponent.PropsOf[C], Any, Any]],
          id,
          props
        )
      }.flatMap(request => journal.componentUpdates.update(_ :+ request))

final private class DeferredRootHooks[Msg, Model] extends RootHooks[Msg, Model]:
  val browserEvent: RootBrowserEventHooks[Msg, Model] = new RootBrowserEventHooks[Msg, Model]:
    def attach[A: JsonDecoder](
      id: String,
      event: BrowserToServerEvent[A]
    )(
      hook: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
    ): LiveIO[Unit] = Deferred.fail("attach browser event hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach browser event hook")

  val event: RootEventHooks[Msg, Model] = new RootEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = Deferred.fail("attach event hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach event hook")

  val params: RootParamsHooks[Msg, Model] = new RootParamsHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = Deferred.fail("attach params hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach params hook")

  val info: RootInfoHooks[Msg, Model] = new RootInfoHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ): LiveIO[Unit] = Deferred.fail("attach info hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach info hook")

  val async: RootAsyncHooks[Msg, Model] = new RootAsyncHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
        LiveHookResult[Model]
      ]
    ): LiveIO[Unit] = Deferred.fail("attach async hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach async hook")

  val afterRender: RootAfterRenderHooks[Msg, Model] = new RootAfterRenderHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]
    ): LiveIO[Unit] = Deferred.fail("attach after-render hook")
    def detach(id: String): LiveIO[Unit] = Deferred.fail("detach after-render hook")
end DeferredRootHooks

final private class JournaledRootHooks[Msg, Model](journal: RootTurnJournal)
    extends RootHooks[Msg, Model]:
  val browserEvent: RootBrowserEventHooks[Msg, Model] = new RootBrowserEventHooks[Msg, Model]:
    def attach[A: JsonDecoder](
      id: String,
      event: BrowserToServerEvent[A]
    )(
      hook: (Model, A, MessageContext[Msg, Model]) => LiveIO[Model]
    ) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(
        dynamicBrowser = RootHookRegistry.replace(
          registry.dynamicBrowser,
          id,
          RootHookRegistry.browserHook(event, summon[JsonDecoder[A]], hook)
        )
      )
    )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicBrowser = RootHookRegistry.detach(registry.dynamicBrowser, id))
    )

  val event: RootEventHooks[Msg, Model] = new RootEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ) =
      journal.updateHooks[Msg, Model](registry =>
        registry.copy(
          dynamicEvent = RootHookRegistry.replace(
            registry.dynamicEvent,
            id,
            new RootHookRegistry.Event[Msg, Model]:
              def invoke(model: Model, message: Msg, context: MessageContext[Msg, Model]) =
                hook(model, message, context)
          )
        )
      )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicEvent = RootHookRegistry.detach(registry.dynamicEvent, id))
    )

  val params: RootParamsHooks[Msg, Model] = new RootParamsHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ) =
      journal.updateHooks[Msg, Model](registry =>
        registry.copy(
          dynamicParams = RootHookRegistry.replace(
            registry.dynamicParams,
            id,
            new RootHookRegistry.Params[Msg, Model]:
              def invoke(model: Model, url: URL, context: ParamsContext[Msg, Model]) =
                hook(model, url, context)
          )
        )
      )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicParams = RootHookRegistry.detach(registry.dynamicParams, id))
    )

  val info: RootInfoHooks[Msg, Model] = new RootInfoHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
    ) =
      journal.updateHooks[Msg, Model](registry =>
        registry.copy(
          dynamicInfo = RootHookRegistry.replace(
            registry.dynamicInfo,
            id,
            new RootHookRegistry.Event[Msg, Model]:
              def invoke(model: Model, message: Msg, context: MessageContext[Msg, Model]) =
                hook(model, message, context)
          )
        )
      )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicInfo = RootHookRegistry.detach(registry.dynamicInfo, id))
    )

  val async: RootAsyncHooks[Msg, Model] = new RootAsyncHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
        LiveHookResult[Model]
      ]
    ) =
      journal.updateHooks[Msg, Model](registry =>
        registry.copy(
          dynamicAsync = RootHookRegistry.replace(
            registry.dynamicAsync,
            id,
            new RootHookRegistry.Async[Msg, Model]:
              def invoke(
                model: Model,
                event: LiveAsyncEvent[Msg],
                context: MessageContext[Msg, Model]
              ) = hook(model, event, context)
          )
        )
      )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicAsync = RootHookRegistry.detach(registry.dynamicAsync, id))
    )

  val afterRender: RootAfterRenderHooks[Msg, Model] = new RootAfterRenderHooks[Msg, Model]:
    def attach(id: String)(hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Unit]) =
      journal.updateHooks[Msg, Model](registry =>
        registry.copy(
          dynamicAfterRender = RootHookRegistry.replace(
            registry.dynamicAfterRender,
            id,
            new RootHookRegistry.AfterRender[Msg, Model]:
              def invoke(model: Model, context: AfterRenderContext[Msg, Model]) =
                hook(model, context)
          )
        )
      )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicAfterRender = RootHookRegistry.detach(registry.dynamicAfterRender, id))
    )
end JournaledRootHooks

final private class RootConnected[Msg](metadata: RootConnectionMetadata, journal: RootTurnJournal)
    extends RootMountConnected[Msg]:
  val staticChanged: Boolean            = metadata.staticChanged
  val connectParams: Map[String, Json]  = metadata.connectParams
  val async: Async[Msg]                 = DeferredAsync()
  val subscriptions: Subscriptions[Msg] = DeferredSubscriptions()
  val client: Client                    = JournaledClient(journal)

final private[connection] class RootMountContext[Msg, Model] private (
  val connection: Connection[RootMountConnected[Msg]],
  val nav: MountNavigation,
  val hooks: RootHooks[Msg, Model],
  val flash: Flash)
    extends MountContext[Msg, Model]:
  val uploads: Uploads = DeferredUploads
  val streams: Streams = DeferredStreams

private[scalive] object RootMountContext:
  def connected[Msg, Model](
    metadata: RootConnectionMetadata,
    currentUrl: URL,
    journal: RootTurnJournal
  ): RootMountContext[Msg, Model] =
    RootMountContext(
      Connection.Connected(RootConnected(metadata, journal)),
      new RootMountNavigation(currentUrl, journal),
      JournaledRootHooks(journal),
      JournaledFlash(journal)
    )

  def disconnected[Msg, Model]: MountContext[Msg, Model] =
    RootMountContext(
      Connection.Disconnected,
      new MountNavigation:
        def pushNavigateUnsafe(to: String)    = Deferred.fail("push navigate")
        def replaceNavigateUnsafe(to: String) = Deferred.fail("replace navigate")
        def redirectUnsafe(to: String)        = Deferred.fail("redirect")
      ,
      DeferredRootHooks(),
      DeferredFlash
    )

  private[connection] def disconnected[Msg, Model](
    currentUrl: URL,
    journal: RootTurnJournal
  ): MountContext[Msg, Model] =
    RootMountContext(
      Connection.Disconnected,
      new RootMountNavigation(currentUrl, journal),
      JournaledRootHooks(journal),
      JournaledFlash(journal)
    )
end RootMountContext

final private[connection] class RootMessageContext[Msg, Model](
  metadata: RootConnectionMetadata,
  currentUrl: URL,
  journal: RootTurnJournal)
    extends MessageContext[Msg, Model]:
  val staticChanged: Boolean            = metadata.staticChanged
  val connectParams: Map[String, Json]  = metadata.connectParams
  val nav: Navigation                   = new RootNavigation(currentUrl, journal, allowPatch = true)
  val flash: Flash                      = JournaledFlash(journal)
  val uploads: Uploads                  = DeferredUploads
  val streams: Streams                  = DeferredStreams
  val async: Async[Msg]                 = DeferredAsync()
  val subscriptions: Subscriptions[Msg] = DeferredSubscriptions()
  val client: Client                    = JournaledClient(journal)
  val components: ComponentUpdates      = JournaledComponentUpdates(journal)
  val hooks: RootHooks[Msg, Model]      = JournaledRootHooks(journal)

final private[connection] class RootParamsContext[Msg, Model](
  metadata: RootConnectionMetadata,
  currentUrl: URL,
  journal: RootTurnJournal,
  connected: Boolean)
    extends ParamsContext[Msg, Model]:
  val connection: Connection[RootParamsConnected[Msg]] =
    if connected then
      Connection.Connected(new RootParamsConnected[Msg]:
        val staticChanged                     = metadata.staticChanged
        val connectParams                     = metadata.connectParams
        val async: Async[Msg]                 = DeferredAsync()
        val subscriptions: Subscriptions[Msg] = DeferredSubscriptions()
        val client: Client                    = JournaledClient(journal)
        val components: ComponentUpdates      = JournaledComponentUpdates(journal))
    else Connection.Disconnected
  val nav: Navigation              = new RootNavigation(currentUrl, journal, allowPatch = true)
  val flash: Flash                 = JournaledFlash(journal)
  val uploads: Uploads             = DeferredUploads
  val streams: Streams             = DeferredStreams
  val hooks: RootHooks[Msg, Model] = JournaledRootHooks(journal)

final private[connection] class RootAfterRenderContext[Msg, Model](
  metadata: RootConnectionMetadata,
  journal: RootTurnJournal,
  connected: Boolean = true)
    extends AfterRenderContext[Msg, Model]:
  val connection: Connection[RootAfterRenderConnected] =
    if connected then
      Connection.Connected(
        new RootAfterRenderConnected:
          val staticChanged = metadata.staticChanged
          val connectParams = metadata.connectParams
          val client        = JournaledClient(journal)
      )
    else Connection.Disconnected
  val hooks: RootHooks[Msg, Model] = JournaledRootHooks(journal)
