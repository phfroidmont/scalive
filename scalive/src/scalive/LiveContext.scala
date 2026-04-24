package scalive

import zio.*
import zio.json.*

import scalive.streams.*
import scalive.upload.*

final case class LiveContext(
  staticChanged: Boolean,
  uploads: UploadRuntime = UploadRuntime.Disabled,
  streams: StreamRuntime = StreamRuntime.Disabled,
  clientEvents: ClientEventRuntime = ClientEventRuntime.Disabled,
  navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled,
  title: TitleRuntime = TitleRuntime.Disabled)
    extends LiveContext.NavigationCapabilities

object LiveContext:
  trait HasStaticChanged:
    def staticChanged: Boolean

  trait HasUploads:
    def uploads: UploadRuntime

  trait HasStreams:
    def streams: StreamRuntime

  trait HasClientEvents:
    def clientEvents: ClientEventRuntime

  trait HasNavigation:
    private[scalive] def navigation: LiveNavigationRuntime

  trait HasTitle:
    def title: TitleRuntime

  trait BaseCapabilities
      extends HasStaticChanged
      with HasUploads
      with HasStreams
      with HasClientEvents
      with HasTitle
  trait NavigationCapabilities extends BaseCapabilities with HasNavigation

  def staticChanged: URIO[HasStaticChanged, Boolean] =
    ZIO.serviceWith[HasStaticChanged](_.staticChanged)

  def allowUpload(name: String, options: LiveUploadOptions): RIO[HasUploads, LiveUpload] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.allow(name, options))

  def disallowUpload(name: String): RIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.disallow(name))

  def upload(name: String): URIO[HasUploads, Option[LiveUpload]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.get(name))

  def cancelUpload(name: String, entryRef: String): RIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.cancel(name, entryRef))

  def consumeUploadedEntries(name: String): URIO[HasUploads, List[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.consumeCompleted(name))

  def consumeUploadedEntry(entryRef: String): URIO[HasUploads, Option[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.consume(entryRef))

  def dropUploadedEntry(entryRef: String): URIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.drop(entryRef))

  def stream[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last,
    reset: Boolean = false,
    limit: Option[StreamLimit] = None
  ): RIO[HasStreams, LiveStream[A]] =
    ZIO.serviceWithZIO[HasStreams](_.streams.stream(definition, items, at, reset, limit))

  def streamInsert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt = StreamAt.Last,
    limit: Option[StreamLimit] = None,
    updateOnly: Boolean = false
  ): RIO[HasStreams, LiveStream[A]] =
    ZIO.serviceWithZIO[HasStreams](_.streams.insert(definition, item, at, limit, updateOnly))

  def streamDelete[A](
    definition: LiveStreamDef[A],
    item: A
  ): RIO[HasStreams, LiveStream[A]] =
    ZIO.serviceWithZIO[HasStreams](_.streams.delete(definition, item))

  def streamDeleteByDomId[A](
    definition: LiveStreamDef[A],
    domId: String
  ): RIO[HasStreams, LiveStream[A]] =
    ZIO.serviceWithZIO[HasStreams](_.streams.deleteByDomId(definition, domId))

  def streamState[A](
    definition: LiveStreamDef[A]
  ): URIO[HasStreams, Option[LiveStream[A]]] =
    ZIO.serviceWithZIO[HasStreams](_.streams.get(definition))

  def pushEvent[A: JsonEncoder](
    name: String,
    payload: A
  ): URIO[HasClientEvents, Unit] =
    payload.toJsonAST match
      case Right(encoded) =>
        ZIO.serviceWithZIO[HasClientEvents](_.clientEvents.push(name, encoded))
      case Left(error) =>
        ZIO.logWarning(s"Could not encode client event '$name': $error")

  final private case class PushJsPayload(cmd: String) derives JsonEncoder

  def pushJs(command: JSCommands.JSCommand): URIO[HasClientEvents, Unit] =
    import JSCommands.JSCommand.given
    pushEvent("js:exec", PushJsPayload(command.toJson))

  def pushPatch(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.PushPatch(to)))

  def pushPatch[A](to: LiveQueryCodec[A], value: A): RIO[HasNavigation, Unit] =
    ZIO
      .fromEither(to.href(value))
      .flatMap(pushPatch)

  def replacePatch(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.ReplacePatch(to)))

  def replacePatch[A](to: LiveQueryCodec[A], value: A): RIO[HasNavigation, Unit] =
    ZIO
      .fromEither(to.href(value))
      .flatMap(replacePatch)

  def putTitle(title: String): URIO[HasTitle, Unit] =
    ZIO.serviceWithZIO[HasTitle](_.title.set(title))
end LiveContext
