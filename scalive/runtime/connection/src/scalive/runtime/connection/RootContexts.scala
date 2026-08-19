package scalive.runtime.connection

import scala.reflect.ClassTag

import zio.Task
import zio.ZIO
import zio.http.URL
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.streams.*
import scalive.upload.*

private object Deferred:
  final case class Unsupported(operation: String)
      extends RuntimeException(s"$operation is not available in this root lifecycle")

  def fail[A](operation: String): LiveIO[A] = ZIO.fail(Unsupported(operation))

private object DeferredMountNavigation extends MountNavigation:
  def pushNavigateUnsafe(to: String): LiveIO[Unit]    = Deferred.fail("push navigate")
  def replaceNavigateUnsafe(to: String): LiveIO[Unit] = Deferred.fail("replace navigate")
  def redirectUnsafe(to: String): LiveIO[Unit]        = Deferred.fail("redirect")

private object DeferredNavigation extends Navigation:
  def pushNavigateUnsafe(to: String): LiveIO[Unit]    = Deferred.fail("push navigate")
  def replaceNavigateUnsafe(to: String): LiveIO[Unit] = Deferred.fail("replace navigate")
  def redirectUnsafe(to: String): LiveIO[Unit]        = Deferred.fail("redirect")
  def pushPatchUnsafe(to: String): LiveIO[Unit]       = Deferred.fail("push patch")
  def replacePatchUnsafe(to: String): LiveIO[Unit]    = Deferred.fail("replace patch")

private object DeferredFlash extends Flash:
  def put(kind: FlashKind, message: String): LiveIO[Unit] = Deferred.fail("put flash")
  def clear(kind: FlashKind): LiveIO[Unit]                = Deferred.fail("clear flash")
  def clearAll: LiveIO[Unit]                              = Deferred.fail("clear all flash")
  def get(kind: FlashKind): LiveIO[Option[String]]        = Deferred.fail("get flash")
  def snapshot: LiveIO[Map[FlashKind, String]]            = Deferred.fail("snapshot flash")

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

private object DeferredClient extends Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): LiveIO[Unit] =
    Deferred.fail("push client event")
  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit] =
    Deferred.fail("execute client command")

private object DeferredComponentUpdates extends ComponentUpdates:
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): LiveIO[Unit] = Deferred.fail("send component update")
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): LiveIO[Unit] = Deferred.fail("send component output update")
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): LiveIO[Unit] = Deferred.fail("send component update by id")

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

final private class RootConnected[Msg](metadata: RootConnectionMetadata)
    extends RootMountConnected[Msg]:
  val staticChanged: Boolean            = metadata.staticChanged
  val connectParams: Map[String, Json]  = metadata.connectParams
  val async: Async[Msg]                 = DeferredAsync()
  val subscriptions: Subscriptions[Msg] = DeferredSubscriptions()
  val client: Client                    = DeferredClient

final private[connection] class RootMountContext[Msg, Model] private (
  val connection: Connection[RootMountConnected[Msg]])
    extends MountContext[Msg, Model]:
  val nav: MountNavigation         = DeferredMountNavigation
  val flash: Flash                 = DeferredFlash
  val uploads: Uploads             = DeferredUploads
  val streams: Streams             = DeferredStreams
  val hooks: RootHooks[Msg, Model] = DeferredRootHooks()

private[scalive] object RootMountContext:
  def connected[Msg, Model](metadata: RootConnectionMetadata): RootMountContext[Msg, Model] =
    RootMountContext(Connection.Connected(RootConnected(metadata)))

  def disconnected[Msg, Model]: MountContext[Msg, Model] =
    RootMountContext(Connection.Disconnected)

final private[connection] class RootMessageContext[Msg, Model](metadata: RootConnectionMetadata)
    extends MessageContext[Msg, Model]:
  val staticChanged: Boolean            = metadata.staticChanged
  val connectParams: Map[String, Json]  = metadata.connectParams
  val nav: Navigation                   = DeferredNavigation
  val flash: Flash                      = DeferredFlash
  val uploads: Uploads                  = DeferredUploads
  val streams: Streams                  = DeferredStreams
  val async: Async[Msg]                 = DeferredAsync()
  val subscriptions: Subscriptions[Msg] = DeferredSubscriptions()
  val client: Client                    = DeferredClient
  val components: ComponentUpdates      = DeferredComponentUpdates
  val hooks: RootHooks[Msg, Model]      = DeferredRootHooks()
