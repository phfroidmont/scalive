package scalive

import scala.reflect.ClassTag

import zio.Task
import zio.json.JsonEncoder
import zio.stream.ZStream

import scalive.streams.*
import scalive.upload.*

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
  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): LiveIO[LiveStream[A]]
  def insertAll[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): LiveIO[LiveStream[A]]
  def reset[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): LiveIO[LiveStream[A]]
  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): LiveIO[LiveStream[A]]
  def delete[A](definition: LiveStreamDef[A], item: A): LiveIO[LiveStream[A]]
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): LiveIO[LiveStream[A]]

trait Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: LiveAsyncResult[A] => Msg): LiveIO[Unit]
  def cancel[A](key: AsyncKey[A], reason: Option[String] = None): LiveIO[Unit]

trait Subscriptions[Msg]:
  def start(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): LiveIO[Unit]
  def replace(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): LiveIO[Unit]
  def cancel(key: SubscriptionKey): LiveIO[Unit]

trait Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): LiveIO[Unit]
  def exec[Msg](js: JSCommands.JSCommand[Msg]): LiveIO[Unit]

trait ComponentUpdates:
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): LiveIO[Unit]
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): LiveIO[Unit]
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): LiveIO[Unit]
