package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*

import scalive.streams.*
import scalive.upload.*

final case class LiveContext private[scalive] (
  staticChanged: Boolean,
  connected: Boolean = false,
  private[scalive] val uploads: UploadRuntime = UploadRuntime.Disabled,
  private[scalive] val streams: StreamRuntime = StreamRuntime.Disabled,
  private[scalive] val clientEvents: ClientEventRuntime = ClientEventRuntime.Disabled,
  private[scalive] val navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled,
  private[scalive] val title: TitleRuntime = TitleRuntime.Disabled,
  private[scalive] val components: ComponentUpdateRuntime = ComponentUpdateRuntime.Disabled,
  private[scalive] val nestedLiveViews: NestedLiveViewRuntime = NestedLiveViewRuntime.Disabled,
  private[scalive] val flash: FlashRuntime = FlashRuntime.Disabled,
  private[scalive] val async: LiveAsyncRuntime = LiveAsyncRuntime.Disabled,
  private[scalive] val hooks: LiveHookRuntime = LiveHookRuntime.Disabled)
    extends LiveContext.NavigationCapabilities

object LiveContext:
  trait HasConnected:
    def connected: Boolean

  trait HasStaticChanged:
    def staticChanged: Boolean

  trait HasUploads:
    private[scalive] def uploads: UploadRuntime

  trait HasStreams:
    private[scalive] def streams: StreamRuntime

  trait HasClientEvents:
    private[scalive] def clientEvents: ClientEventRuntime

  trait HasNavigation:
    private[scalive] def navigation: LiveNavigationRuntime

  trait HasTitle:
    private[scalive] def title: TitleRuntime

  trait HasComponents:
    private[scalive] def components: ComponentUpdateRuntime

  trait HasNestedLiveViews:
    private[scalive] def nestedLiveViews: NestedLiveViewRuntime

  trait HasFlash:
    private[scalive] def flash: FlashRuntime

  trait HasAsync:
    private[scalive] def async: LiveAsyncRuntime

  trait HasHooks:
    private[scalive] def hooks: LiveHookRuntime

  trait BaseCapabilities
      extends HasConnected
      with HasStaticChanged
      with HasUploads
      with HasStreams
      with HasClientEvents
      with HasTitle
      with HasComponents
      with HasNestedLiveViews
      with HasFlash
      with HasAsync
      with HasHooks
  trait NavigationCapabilities extends BaseCapabilities with HasNavigation

  def staticChanged: URIO[HasStaticChanged, Boolean] =
    ZIO.serviceWith[HasStaticChanged](_.staticChanged)

  def connected: URIO[HasConnected, Boolean] =
    ZIO.serviceWith[HasConnected](_.connected)

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

  def pushJs[Msg](command: JSCommands.JSCommand[Msg]): URIO[HasClientEvents, Unit] =
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

  def pushNavigate(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.PushNavigate(to)))

  def pushNavigate[A](to: LiveQueryCodec[A], value: A): RIO[HasNavigation, Unit] =
    ZIO
      .fromEither(to.href(value))
      .flatMap(pushNavigate)

  def replaceNavigate(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](
      _.navigation.request(LiveNavigationCommand.ReplaceNavigate(to))
    )

  def replaceNavigate[A](to: LiveQueryCodec[A], value: A): RIO[HasNavigation, Unit] =
    ZIO
      .fromEither(to.href(value))
      .flatMap(replaceNavigate)

  def redirect(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.Redirect(to)))

  def redirect[A](to: LiveQueryCodec[A], value: A): RIO[HasNavigation, Unit] =
    ZIO
      .fromEither(to.href(value))
      .flatMap(redirect)

  def putTitle(title: String): URIO[HasTitle, Unit] =
    ZIO.serviceWithZIO[HasTitle](_.title.set(title))

  def putFlash(kind: String, message: String): URIO[HasFlash, Unit] =
    ZIO.serviceWithZIO[HasFlash](_.flash.put(kind, message))

  def clearFlash(kind: String): URIO[HasFlash, Unit] =
    ZIO.serviceWithZIO[HasFlash](_.flash.clear(kind))

  def clearFlash: URIO[HasFlash, Unit] =
    ZIO.serviceWithZIO[HasFlash](_.flash.clearAll)

  def flash(kind: String): URIO[HasFlash, Option[String]] =
    ZIO.serviceWithZIO[HasFlash](_.flash.get(kind))

  def flash: URIO[HasFlash, Map[String, String]] =
    ZIO.serviceWithZIO[HasFlash](_.flash.snapshot)

  def startAsync[A, Msg](
    name: String,
    mode: AsyncStartMode = AsyncStartMode.Restart
  )(
    effect: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): URIO[HasAsync, Unit] =
    ZIO.serviceWithZIO[HasAsync](_.async.start(name, mode)(effect)(toMsg))

  inline def assignAsync[Model, A](
    model: Model,
    mode: AsyncStartMode = AsyncStartMode.Restart,
    reset: Boolean = false
  )(
    inline field: Model => AsyncValue[A]
  )(
    effect: Task[A]
  ): URIO[HasAsync, Model] =
    ${ LiveContextMacros.assignAsyncImpl[Model, A]('model, 'mode, 'reset, 'field, 'effect) }

  def cancelAsync(
    name: String,
    reason: Option[String] = None
  ): URIO[HasAsync, Unit] =
    ZIO.serviceWithZIO[HasAsync](_.async.cancel(name, reason))

  inline def cancelAssignAsync[Model, A](
    model: Model
  )(
    inline field: Model => AsyncValue[A],
    reason: Option[String] = None
  ): URIO[HasAsync, Unit] =
    ${ LiveContextMacros.cancelAssignAsyncImpl[Model, A]('model, 'field, 'reason) }

  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): URIO[HasComponents, Unit] =
    ZIO.serviceWithZIO[HasComponents](
      _.components.sendUpdate(summon[ClassTag[C]].runtimeClass, id, props)
    )

  def attachEventHook[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent) => LiveIO[LiveView.UpdateContext, LiveEventResult[Model]]
  ): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.attachEvent(id)(hook))

  def detachEventHook(id: String): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.detachEvent(id))

  def attachParamsHook[Model](
    id: String
  )(
    hook: (Model, URL) => LiveIO[LiveView.ParamsContext, LiveHookResult[Model]]
  ): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.attachParams(id)(hook))

  def detachParamsHook(id: String): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.detachParams(id))

  def attachInfoHook[Msg, Model](
    id: String
  )(
    hook: (Model, Msg) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.attachInfo(id)(hook))

  def detachInfoHook(id: String): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.detachInfo(id))

  def attachAsyncHook[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveAsyncEvent) => LiveIO[LiveView.UpdateContext, LiveHookResult[Model]]
  ): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.attachAsync(id)(hook))

  def detachAsyncHook(id: String): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.detachAsync(id))

  def attachAfterRenderHook[Model](
    id: String
  )(
    hook: Model => LiveIO[LiveView.UpdateContext, Model]
  ): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.attachAfterRender(id)(hook))

  def detachAfterRenderHook(id: String): RIO[HasHooks, Unit] =
    ZIO.serviceWithZIO[HasHooks](_.hooks.detachAfterRender(id))
end LiveContext
