package scalive

import scala.reflect.ClassTag

import zio.Task
import zio.UIO
import zio.json.JsonEncoder
import zio.stream.ZStream

import scalive.streams.*
import scalive.upload.*

trait MountNavigation:
  def pushNavigate(to: LiveLocation): Task[Unit] = pushNavigateUnsafe(to.href)
  def pushNavigateUnsafe(to: String): Task[Unit]
  def replaceNavigate(to: LiveLocation): Task[Unit] = replaceNavigateUnsafe(to.href)
  def replaceNavigateUnsafe(to: String): Task[Unit]
  def redirect(to: LiveLocation): Task[Unit] = redirectUnsafe(to.href)
  def redirectUnsafe(to: String): Task[Unit]

trait Navigation extends MountNavigation:
  def pushPatch(to: LiveLocation): Task[Unit] = pushPatchUnsafe(to.href)
  def pushPatchUnsafe(to: String): Task[Unit]
  def replacePatch(to: LiveLocation): Task[Unit] = replacePatchUnsafe(to.href)
  def replacePatchUnsafe(to: String): Task[Unit]

trait Flash:
  def put(kind: FlashKind, message: String): Task[Unit]
  def clear(kind: FlashKind): Task[Unit]
  def clearAll: Task[Unit]
  def get(kind: FlashKind): Task[Option[String]]
  def snapshot: Task[Map[FlashKind, String]]

trait Uploads:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]]
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]
  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]]
  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]]
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])]
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])]

trait Streams:
  def create[A, Id](definition: LiveStreamDef[A, Id], items: Iterable[A]): Task[LiveStream[A]]
  def insertAll[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[LiveStream[A]]
  def reset[A, Id](
    definition: LiveStreamDef[A, Id],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Task[LiveStream[A]]
  def insert[A, Id](
    definition: LiveStreamDef[A, Id],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): Task[LiveStream[A]]
  def delete[A, Id](definition: LiveStreamDef[A, Id], id: Id): Task[LiveStream[A]]
  def deleteByDomId[A, Id](definition: LiveStreamDef[A, Id], domId: String): Task[LiveStream[A]]

trait Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: LiveAsyncResult[A] => Msg): Task[Unit]
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): Task[Unit]

trait Subscriptions[Msg]:
  def start(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]
  def replace(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit]
  def cancel(key: SubscriptionKey): Task[Unit]

/** Acquisition and finalization owned by one connected `LiveView` lifecycle.
  *
  * This capability is exposed through [[RootMountConnected.resources]]. Applications should use it
  * during connected mount and must not retain it for later acquisition.
  */
trait ConnectedResources:
  /** Acquires a resource and registers its finalizer with the current connected lifecycle.
    *
    * Acquisition and finalization run uninterruptibly. When `acquire` succeeds, `release` is
    * registered before the acquired value is returned and runs exactly once when the lifecycle
    * closes. If `acquire` fails, `release` does not run. Calls made after lifecycle closure fail
    * before acquisition starts.
    *
    * Keep both effects short and bounded because either can delay lifecycle shutdown. Recover any
    * expected cleanup failure inside the `UIO` finalizer.
    */
  def acquireRelease[A](acquire: Task[A])(release: A => UIO[Unit]): Task[A]

trait Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): Task[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit]

trait ComponentUpdates:
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): Task[Unit]
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): Task[Unit]
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): Task[Unit]
