package scalive.runtime.connection

import scala.reflect.ClassTag
import scala.util.Try

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
import scalive.runtime.contracts.Epoch
import scalive.runtime.kernel.*
import scalive.runtime.resources.*
import scalive.streams.*
import scalive.upload.*

private object Deferred:
  final case class Unsupported(operation: String)
      extends RuntimeException(s"$operation is not available in this root lifecycle")

  def fail[A](operation: String): Task[A] = ZIO.fail(Unsupported(operation))

final private[scalive] class RootTurnJournal private (
  val owner: OwnerId,
  val ownerEpoch: Epoch,
  val navigation: Ref[Option[NavigationRequest]],
  val hooks: Ref[RootHookRegistry[Any, Any]],
  val flash: Ref[Map[FlashKind, String]],
  val clientEvents: Ref[Vector[ClientEffect]],
  val componentUpdates: Ref[Vector[ComponentUpdateRequest]],
  val resourceOperations: Ref[Vector[ResourceOperation]],
  val streams: Ref[StreamStore],
  val uploads: Ref[UploadRegistry],
  val uploadCommit: Ref[UploadRetirementPlan],
  val uploadRollback: Ref[UploadRetirementPlan]):

  def hookRegistry[Msg, Model]: UIO[RootHookRegistry[Msg, Model]] =
    hooks.get.map(_.asInstanceOf[RootHookRegistry[Msg, Model]])

  def updateHooks[Msg, Model](
    f: RootHookRegistry[Msg, Model] => RootHookRegistry[Msg, Model]
  ): UIO[Unit] = hooks.update(value =>
    f(value.asInstanceOf[RootHookRegistry[Msg, Model]]).asInstanceOf[RootHookRegistry[Any, Any]]
  )

  def navigationWithFlash: UIO[Option[NavigationRequest]] =
    navigation.get.zipWith(flash.get)((request, values) => request.map(_.copy(flash = values)))

  def record(operation: ResourceOperation): UIO[Unit] =
    resourceOperations.update(_ :+ operation)

  def resourceOperationSnapshot: UIO[Vector[ResourceOperation]] = resourceOperations.get

  def streamSnapshot: UIO[StreamStore] = streams.get.map(_.prune)

  def recordUploadCommit(plan: UploadRetirementPlan): UIO[Unit] =
    uploadCommit.update(_ ++ plan)

  def recordUploadRollback(plan: UploadRetirementPlan): UIO[Unit] =
    uploadRollback.update(_ ++ plan)

  def uploadSnapshot: UIO[(UploadRegistry, UploadRetirementPlan, UploadRetirementPlan)] =
    uploads.get.zipWith(uploadCommit.get)(_ -> _).zipWith(uploadRollback.get) {
      case ((registry, commit), rollback) => (registry, commit, rollback)
    }

  def scoped(scopedOwner: OwnerId): RootTurnJournal =
    new RootTurnJournal(
      scopedOwner,
      ownerEpoch,
      navigation,
      hooks,
      flash,
      clientEvents,
      componentUpdates,
      resourceOperations,
      streams,
      uploads,
      uploadCommit,
      uploadRollback
    )
end RootTurnJournal

private[scalive] object RootTurnJournal:
  def make[Msg, Model](
    owner: OwnerId,
    registry: RootHookRegistry[Msg, Model],
    initialFlash: Map[FlashKind, String] = Map.empty,
    initialClientEvents: Vector[ClientEffect] = Vector.empty,
    initialComponentUpdates: Vector[ComponentUpdateRequest] = Vector.empty,
    initialNavigation: Option[NavigationRequest] = None,
    initialResourceOperations: Vector[ResourceOperation] = Vector.empty,
    initialStreams: StreamStore = StreamStore.empty,
    ownerEpoch: Epoch = Epoch.initial,
    initialUploads: UploadRegistry = UploadRegistry.empty,
    initialUploadCommit: UploadRetirementPlan = UploadRetirementPlan.empty,
    initialUploadRollback: UploadRetirementPlan = UploadRetirementPlan.empty
  ): Task[RootTurnJournal] =
    for
      navigation     <- Ref.make(initialNavigation)
      hooks          <- Ref.make(registry.asInstanceOf[RootHookRegistry[Any, Any]])
      flash          <- Ref.make(initialFlash)
      clientEvents   <- Ref.make(initialClientEvents)
      updates        <- Ref.make(initialComponentUpdates)
      operations     <- Ref.make(initialResourceOperations)
      streams        <- Ref.make(initialStreams)
      uploads        <- Ref.make(initialUploads)
      uploadCommit   <- Ref.make(initialUploadCommit)
      uploadRollback <- Ref.make(initialUploadRollback)
    yield new RootTurnJournal(
      owner,
      ownerEpoch,
      navigation,
      hooks,
      flash,
      clientEvents,
      updates,
      operations,
      streams,
      uploads,
      uploadCommit,
      uploadRollback
    )
end RootTurnJournal

final private class RootNavigation(
  currentUrl: URL,
  journal: RootTurnJournal,
  allowPatch: Boolean)
    extends Navigation:
  def pushNavigateUnsafe(to: String): Task[Unit] =
    record(to, NavigationKind.PushNavigate)
  def replaceNavigateUnsafe(to: String): Task[Unit] =
    record(to, NavigationKind.ReplaceNavigate)
  def redirectUnsafe(to: String): Task[Unit]  = record(to, NavigationKind.Redirect)
  def pushPatchUnsafe(to: String): Task[Unit] =
    if allowPatch then record(to, NavigationKind.PushPatch)
    else Deferred.fail("push patch")
  def replacePatchUnsafe(to: String): Task[Unit] =
    if allowPatch then record(to, NavigationKind.ReplacePatch)
    else Deferred.fail("replace patch")

  private def record(to: String, kind: NavigationKind): Task[Unit] =
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
  def pushNavigateUnsafe(to: String): Task[Unit]    = navigation.pushNavigateUnsafe(to)
  def replaceNavigateUnsafe(to: String): Task[Unit] = navigation.replaceNavigateUnsafe(to)
  def redirectUnsafe(to: String): Task[Unit]        = navigation.redirectUnsafe(to)

private object DeferredFlash extends Flash:
  def put(kind: FlashKind, message: String): Task[Unit] = Deferred.fail("put flash")
  def clear(kind: FlashKind): Task[Unit]                = Deferred.fail("clear flash")
  def clearAll: Task[Unit]                              = Deferred.fail("clear all flash")
  def get(kind: FlashKind): Task[Option[String]]        = Deferred.fail("get flash")
  def snapshot: Task[Map[FlashKind, String]]            = Deferred.fail("snapshot flash")

final private class JournaledFlash(journal: RootTurnJournal) extends Flash:
  def put(kind: FlashKind, message: String): Task[Unit] =
    ZIO.fail(NullPointerException("flash message must not be null")).when(message == null) *>
      journal.flash.update(_.updated(kind, message))
  def clear(kind: FlashKind): Task[Unit]         = journal.flash.update(_ - kind)
  def clearAll: Task[Unit]                       = journal.flash.set(Map.empty)
  def get(kind: FlashKind): Task[Option[String]] = journal.flash.get.map(_.get(kind))
  def snapshot: Task[Map[FlashKind, String]]     = journal.flash.get

private object DeferredUploads extends Uploads:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]] = Deferred.fail("allow upload")
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]       = Deferred.fail("disallow upload")
  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]] =
    Deferred.fail("get upload")
  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]] = Deferred.fail("cancel upload")
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])] = Deferred.fail("consume upload")
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])] = Deferred.fail("consume completed uploads")

final private[connection] class JournaledUploads(journal: RootTurnJournal) extends Uploads:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]] =
    for
      ref    <- UploadRuntime.freshRef
      result <- journal.uploads.modify { current =>
                  current.allow(journal.owner, journal.ownerEpoch, UploadKey(definition), ref) match
                    case Right((next, token)) =>
                      next.snapshotForToken(token).left.map(operationError) -> next
                    case Left(error) => Left(operationError(error)) -> current
                }
      upload <- ZIO.fromEither(result)
    yield upload

  def disallow[R](definition: LiveUploadDef[R]): Task[Unit] = ZIO.uninterruptible {
    journal.uploads
      .modify { current =>
        current.disallow(journal.owner, journal.ownerEpoch, UploadKey(definition)) match
          case Right(removal) => Right(removal)              -> removal.registry
          case Left(error)    => Left(operationError(error)) -> current
      }.flatMap(ZIO.fromEither)
      .flatMap(removal => journal.recordUploadCommit(removal.retirement))
  }

  def get[R](definition: LiveUploadDef[R]): Task[Option[LiveUpload[R]]] =
    journal.uploads.get.flatMap { current =>
      current.get(journal.owner, journal.ownerEpoch, UploadKey(definition)) match
        case Right((_, upload))                      => ZIO.some(upload)
        case Left(UploadRegistryError.NotAllowed(_)) => ZIO.none
        case Left(error)                             => ZIO.fail(operationError(error))
    }

  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]] = ZIO.uninterruptible {
    journal.uploads
      .modify { current =>
        current.cancel(journal.owner, journal.ownerEpoch, entry) match
          case Right(removal) => Right(removal)              -> removal.registry
          case Left(error)    => Left(operationError(error)) -> current
      }.flatMap(ZIO.fromEither)
      .flatMap { removal =>
        journal.recordUploadCommit(removal.retirement) *>
          ZIO.fromEither(
            removal.registry
              .snapshotFor(journal.owner, journal.ownerEpoch, entry).left.map(operationError)
          )
      }
  }

  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])] =
    for
      begin <- journal.uploads.get.flatMap(current =>
                 ZIO.fromEither(
                   current
                     .beginConsume(journal.owner, journal.ownerEpoch, entry)(callback)
                     .left.map(consumeError(_, entry.ref))
                 )
               )
      decision <- begin.operation.run
      result   <- ZIO.uninterruptible {
                  journal.uploads
                    .modify { current =>
                      current.finishConsume(begin, decision) match
                        case Right(value) => Right(value)                         -> value.registry
                        case Left(error)  => Left(consumeError(error, entry.ref)) -> current
                    }.flatMap(ZIO.fromEither)
                    .flatMap { value =>
                      val transfer = value.ownership.fold(UploadRetirementPlan.empty)(operation =>
                        UploadRetirementPlan(
                          Vector(UploadRetirementInstruction.Cleanup(operation))
                        )
                      )
                      journal.recordUploadCommit(transfer) *>
                        ZIO
                          .fromEither(
                            value.registry
                              .snapshotFor(journal.owner, journal.ownerEpoch, entry)
                              .left.map(operationError)
                          ).map(upload => valueOf(decision) -> upload)
                    }
                }
    yield result

  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => Task[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])] =
    for
      current <- get(definition).someOrFail(LiveUploadOperationError.NotAllowed(definition.name))
      _       <- ZIO
             .fail(LiveUploadOperationError.EntriesInProgress(definition.name)).when(
               current.entries.exists(entry =>
                 entry.status match
                   case LiveUploadEntryStatus.Completed | LiveUploadEntryStatus.Invalid(_) => false
                   case _                                                                  => true
               )
             )
      completed = current.entries.filter(_.status == LiveUploadEntryStatus.Completed)
      consumed <- ZIO.foldLeft(completed)(List.empty[A]) { (values, entry) =>
                    consume(entry)(callback).map { case (value, _) => values :+ value }
                  }
      upload <- get(definition).someOrFail(LiveUploadOperationError.NotAllowed(definition.name))
    yield consumed -> upload

  private def valueOf[A](decision: ConsumeDecision[A]): A = decision match
    case ConsumeDecision.Consume(value)  => value
    case ConsumeDecision.Postpone(value) => value

  private def consumeError(
    error: UploadRegistryError,
    ref: UploadEntryRef
  ): Throwable = error match
    case UploadRegistryError.InvalidEntryState(_) => LiveUploadOperationError.EntryNotCompleted(ref)
    case other                                    => operationError(other)

  private def operationError(error: UploadRegistryError): Throwable = error match
    case UploadRegistryError.NotAllowed(name)         => LiveUploadOperationError.NotAllowed(name)
    case UploadRegistryError.DefinitionMismatch(name) =>
      LiveUploadOperationError.DefinitionMismatch(name)
    case UploadRegistryError.ActiveEntries(name) => LiveUploadOperationError.ActiveEntries(name)
    case UploadRegistryError.EntryInactive(ref)  => LiveUploadOperationError.EntryNotActive(ref)
    case other => IllegalStateException(s"Upload operation rejected: $other")
end JournaledUploads

final private class JournaledStreams(streams: Ref[StreamStore], applyLimits: Boolean = true)
    extends Streams:
  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): Task[LiveStream[A]] =
    update(_.create(effective(definition), items))
  def insertAll[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt
  ): Task[LiveStream[A]] = update(_.insertAll(effective(definition), items, at))
  def reset[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt
  ): Task[LiveStream[A]] = update(_.reset(effective(definition), items, at))
  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt,
    updateOnly: Boolean
  ): Task[LiveStream[A]] = update(_.insert(effective(definition), item, at, updateOnly))
  def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]] =
    update(_.delete(effective(definition), item))
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]] =
    update(_.deleteByDomId(effective(definition), domId))

  private def effective[A](definition: LiveStreamDef[A]): LiveStreamDef[A] =
    if applyLimits then definition else definition.withoutLimit

  private def update[A](
    operation: StreamStore => StreamStore.Replacement[A]
  ): Task[LiveStream[A]] =
    streams
      .modify { current =>
        Try(operation(current)).toEither match
          case Right(replacement) => Right(replacement.stream) -> replacement.store
          case Left(error)        => Left(error)               -> current
      }.flatMap(ZIO.fromEither)
end JournaledStreams

private object DeferredStreams extends Streams:
  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): Task[LiveStream[A]] =
    Deferred.fail("create stream")
  def insertAll[A](definition: LiveStreamDef[A], items: Iterable[A], at: StreamAt) =
    Deferred.fail("insert stream items")
  def reset[A](definition: LiveStreamDef[A], items: Iterable[A], at: StreamAt) =
    Deferred.fail("reset stream")
  def insert[A](definition: LiveStreamDef[A], item: A, at: StreamAt, updateOnly: Boolean) =
    Deferred.fail("insert stream item")
  def delete[A](definition: LiveStreamDef[A], item: A) =
    Deferred.fail("delete stream item")
  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String) =
    Deferred.fail("delete stream item by DOM id")

final private class JournaledAsync[Msg](journal: RootTurnJournal) extends Async[Msg]:
  def start[A](key: AsyncKey[A])(task: Task[A])(toMsg: LiveAsyncResult[A] => Msg): Task[Unit] =
    journal.record(ResourceOperation.StartAsync(journal.owner, key, task, toMsg))
  def cancel[A](key: AsyncKey[A], reason: Option[String]): Task[Unit] =
    journal.record(
      ResourceOperation.CancelAsync(journal.owner, key.asInstanceOf[AsyncKey[Any]], reason)
    )

final private class JournaledSubscriptions[Msg](journal: RootTurnJournal)
    extends Subscriptions[Msg]:
  def start(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit] =
    journal.record(
      ResourceOperation.StartSubscription(journal.owner, key, delivery, stream, replace = false)
    )
  def replace(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery
  )(
    stream: ZStream[Any, Nothing, Msg]
  ): Task[Unit] =
    journal.record(
      ResourceOperation.StartSubscription(journal.owner, key, delivery, stream, replace = true)
    )
  def cancel(key: SubscriptionKey): Task[Unit] =
    journal.record(ResourceOperation.CancelSubscription(journal.owner, key))

final private class JournaledClient(journal: RootTurnJournal) extends Client:
  def push[A: JsonEncoder](event: ServerToBrowserEvent[A], payload: A): Task[Unit] =
    payload.toJsonAST match
      case Right(value) => journal.clientEvents.update(_ :+ ClientEffect(event.value, value))
      case Left(error)  =>
        ZIO.fail(
          IllegalArgumentException(s"Could not encode client event '${event.value}': $error")
        )

  def exec[Msg](js: JSCommands.JSCommand[Msg]): Task[Unit] =
    import JSCommands.JSCommand.given
    journal.clientEvents.update(
      _ :+ ClientEffect("js:exec", Json.Obj("cmd" -> Json.Str(js.toJson)))
    )

final private class JournaledComponentUpdates(journal: RootTurnJournal) extends ComponentUpdates:
  def sendUpdate[Props, Msg, Model](
    instance: LiveComponentInstance[Props, Msg, Model],
    props: Props
  ): Task[Unit] = journal.componentUpdates.update(_ :+ ComponentUpdateRequest(instance, props))
  def sendUpdate[Props, Msg, Model, Output](
    instance: LiveComponentOutputInstance[Props, Msg, Model, Output],
    props: Props
  ): Task[Unit] = journal.componentUpdates.update(_ :+ ComponentUpdateRequest(instance, props))
  def sendUpdate[C <: LiveComponent[?, ?, ?]: ClassTag](
    id: String,
    props: LiveComponent.PropsOf[C]
  ): Task[Unit] =
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
  val rawEvent: RootRawEventHooks[Msg, Model] = new RootRawEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]]
    ): Task[Unit]                      = Deferred.fail("attach raw event hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach raw event hook")

  val browserEvent: RootBrowserEventHooks[Msg, Model] = new RootBrowserEventHooks[Msg, Model]:
    def attach[A: JsonDecoder](
      id: String,
      event: BrowserToServerEvent[A]
    )(
      hook: (Model, A, MessageContext[Msg, Model]) => Task[Model]
    ): Task[Unit]                      = Deferred.fail("attach browser event hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach browser event hook")

  val event: RootEventHooks[Msg, Model] = new RootEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
    ): Task[Unit]                      = Deferred.fail("attach event hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach event hook")

  val params: RootParamsHooks[Msg, Model] = new RootParamsHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]]
    ): Task[Unit]                      = Deferred.fail("attach params hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach params hook")

  val info: RootInfoHooks[Msg, Model] = new RootInfoHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
    ): Task[Unit]                      = Deferred.fail("attach info hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach info hook")

  val async: RootAsyncHooks[Msg, Model] = new RootAsyncHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[
        LiveHookResult[Model]
      ]
    ): Task[Unit]                      = Deferred.fail("attach async hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach async hook")

  val afterRender: RootAfterRenderHooks[Msg, Model] = new RootAfterRenderHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit]
    ): Task[Unit]                      = Deferred.fail("attach after-render hook")
    def detach(id: String): Task[Unit] = Deferred.fail("detach after-render hook")
end DeferredRootHooks

final private class JournaledRootHooks[Msg, Model](journal: RootTurnJournal)
    extends RootHooks[Msg, Model]:
  val rawEvent: RootRawEventHooks[Msg, Model] = new RootRawEventHooks[Msg, Model]:
    def attach(
      id: String
    )(
      hook: (Model, LiveEvent, MessageContext[Msg, Model]) => Task[LiveEventHookResult[Model]]
    ) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(
        dynamicRaw = RootHookRegistry.replace(
          registry.dynamicRaw,
          id,
          new RootHookRegistry.Raw[Msg, Model]:
            def invoke(model: Model, event: LiveEvent, context: MessageContext[Msg, Model]) =
              hook(model, event, context)
        )
      )
    )
    def detach(id: String) = journal.updateHooks[Msg, Model](registry =>
      registry.copy(dynamicRaw = RootHookRegistry.detach(registry.dynamicRaw, id))
    )

  val browserEvent: RootBrowserEventHooks[Msg, Model] = new RootBrowserEventHooks[Msg, Model]:
    def attach[A: JsonDecoder](
      id: String,
      event: BrowserToServerEvent[A]
    )(
      hook: (Model, A, MessageContext[Msg, Model]) => Task[Model]
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
      hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
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
      hook: (Model, URL, ParamsContext[Msg, Model]) => Task[LiveHookResult[Model]]
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
      hook: (Model, Msg, MessageContext[Msg, Model]) => Task[LiveHookResult[Model]]
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
      hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => Task[
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
    def attach(id: String)(hook: (Model, AfterRenderContext[Msg, Model]) => Task[Unit]) =
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
  val async: Async[Msg]                 = JournaledAsync(journal)
  val subscriptions: Subscriptions[Msg] = JournaledSubscriptions(journal)
  val client: Client                    = JournaledClient(journal)

final private[connection] class RootMountContext[Msg, Model] private (
  val connection: Connection[RootMountConnected[Msg]],
  val nav: MountNavigation,
  val hooks: RootHooks[Msg, Model],
  val flash: Flash,
  val uploads: Uploads,
  val streams: Streams)
    extends MountContext[Msg, Model]

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
      JournaledFlash(journal),
      JournaledUploads(journal),
      JournaledStreams(journal.streams)
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
      DeferredFlash,
      DeferredUploads,
      DeferredStreams
    )

  private[connection] def disconnected[Msg, Model](
    currentUrl: URL,
    journal: RootTurnJournal
  ): MountContext[Msg, Model] =
    RootMountContext(
      Connection.Disconnected,
      new RootMountNavigation(currentUrl, journal),
      JournaledRootHooks(journal),
      JournaledFlash(journal),
      JournaledUploads(journal),
      JournaledStreams(journal.streams, applyLimits = false)
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
  val uploads: Uploads                  = JournaledUploads(journal)
  val streams: Streams                  = JournaledStreams(journal.streams)
  val async: Async[Msg]                 = JournaledAsync(journal)
  val subscriptions: Subscriptions[Msg] = JournaledSubscriptions(journal)
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
        val async: Async[Msg]                 = JournaledAsync(journal)
        val subscriptions: Subscriptions[Msg] = JournaledSubscriptions(journal)
        val client: Client                    = JournaledClient(journal)
        val components: ComponentUpdates      = JournaledComponentUpdates(journal))
    else Connection.Disconnected
  val nav: Navigation              = new RootNavigation(currentUrl, journal, allowPatch = true)
  val flash: Flash                 = JournaledFlash(journal)
  val uploads: Uploads             = JournaledUploads(journal)
  val streams: Streams             = JournaledStreams(journal.streams, applyLimits = connected)
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
