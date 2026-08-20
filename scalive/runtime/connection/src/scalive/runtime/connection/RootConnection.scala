package scalive.runtime.connection

import zio.*
import zio.http.URL

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.kernel.*
import scalive.runtime.resources.OwnerId

/** Owns one connected, unrouted root lifecycle. */
final private[scalive] class RootConnection[Msg, Model] private (
  val lifecycle: LifecycleId,
  val epoch: Epoch,
  config: ConnectionConfig,
  kernel: SessionKernel[Msg, RootState[Msg, Model]],
  componentEnvironment: ConnectedComponentEnvironment[Msg, Model],
  outbound: InMemoryOutboundReservations[SessionOutput],
  writer: SerialWriter[ConnectionOutput],
  ingress: Queue[RootConnection.Event],
  ingressGate: Semaphore,
  pending: Ref[Map[CommandId, RootConnection.PendingCommand]],
  failure: Promise[Nothing, ConnectionError],
  bootstrapReady: Promise[ConnectionError, Unit],
  closing: Promise[Nothing, Unit],
  closed: Promise[Nothing, Unit]):
  import RootConnection.*

  def submitEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    enqueueEvent(command, binding, payload).flatMap(_.await)

  /** Installs one event in the bounded ingress queue without waiting for its eventual reply. */
  def offerEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    enqueueEvent(command, binding, payload).unit

  private[connection] def submitNamedEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.Browser(command, binding, payload, Some(eventName), Some(rawJson)))
      .flatMap(_.await)

  private[scalive] def offerNamedEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.Browser(command, binding, payload, Some(eventName), Some(rawJson))).unit

  private[connection] def submitComponentNamedEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    enqueue(
      command,
      Event.ComponentBrowser(command, component, binding, payload, Some(eventName), Some(rawJson))
    ).flatMap(_.await)

  private[scalive] def offerComponentNamedEvent(
    command: CommandId,
    component: ComponentInstanceId,
    binding: BindingId,
    payload: BindingPayload,
    eventName: String,
    rawJson: String
  ): IO[ConnectionError, Unit] =
    enqueue(
      command,
      Event.ComponentBrowser(command, component, binding, payload, Some(eventName), Some(rawJson))
    ).unit

  private[connection] def submitComponentMessage[M](
    command: CommandId,
    component: ComponentInstanceId,
    message: M
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentMessage(command, component, message)).flatMap(_.await)

  private[scalive] def offerComponentMessage[M](
    command: CommandId,
    component: ComponentInstanceId,
    message: M
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentMessage(command, component, message)).unit

  private[connection] def submitComponentAsyncCompletion[M](
    command: CommandId,
    component: ComponentInstanceId,
    event: LiveAsyncEvent[M]
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentAsync(command, component, event)).flatMap(_.await)

  private[scalive] def offerComponentAsyncCompletion[M](
    command: CommandId,
    component: ComponentInstanceId,
    event: LiveAsyncEvent[M]
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentAsync(command, component, event)).unit

  private[connection] def submitComponentUpdate(
    command: CommandId,
    component: ComponentInstanceId
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentUpdate(command, component)).flatMap(_.await)

  private[scalive] def offerComponentUpdate(
    command: CommandId,
    component: ComponentInstanceId
  ): IO[ConnectionError, Unit] =
    enqueue(command, Event.ComponentUpdate(command, component)).unit

  def submitPatch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
    enqueuePatch(command, destination).flatMap(_.await)

  def offerPatch(command: CommandId, destination: URL): IO[ConnectionError, Unit] =
    enqueuePatch(command, destination).unit

  private[scalive] def offerInternalPatch(destination: URL): IO[ConnectionError, Unit] =
    for
      command <- ZIO
                   .fromEither(CommandId.fresh()).mapError(error =>
                     ConnectionError.KernelRejected(SessionRejection.IdentityUnavailable(error))
                   )
      _ <- kernel
             .enqueuePatchAcknowledgement(command, epoch, destination).unit
             .mapError(connectionError)
    yield ()

  private[scalive] def synchronizeUrl(destination: URL): IO[ConnectionError, Unit] =
    for
      command <- ZIO
                   .fromEither(CommandId.fresh()).mapError(error =>
                     ConnectionError.KernelRejected(SessionRejection.IdentityUnavailable(error))
                   )
      _ <- submitPatch(command, destination)
    yield ()

  private def enqueueEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    enqueue(command, Event.Browser(command, binding, payload, None, None))

  private def enqueuePatch(
    command: CommandId,
    destination: URL
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    enqueue(command, Event.Patch(command, destination))

  private def enqueue(
    command: CommandId,
    event: Event
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    ZIO.uninterruptible {
      for
        response <- Promise.make[ConnectionError, Unit]
        _        <- ingressGate
               .withPermit {
                 closing.isDone.flatMap {
                   case true  => ZIO.fail(ConnectionError.Closed)
                   case false =>
                     pending.modify { current =>
                       val limit = event match
                         case Event.Patch(_, _) => config.ingressCapacity + 2
                         case _                 => config.ingressCapacity + 1
                       if current.contains(command) then
                         Left(ConnectionError.DuplicateCommand(command)) -> current
                       else if current.size >= limit then
                         Left(ConnectionError.IngressSaturated(config.ingressCapacity)) -> current
                       else
                         val sequence =
                           current.valuesIterator.map(_.sequence).maxOption.fold(0L)(_ + 1L)
                         Right(()) -> current.updated(command, PendingCommand(sequence, response))
                     }.absolve *> ingress.offer(event).flatMap {
                       case true  => ZIO.unit
                       case false =>
                         ZIO.fail(ConnectionError.IngressSaturated(config.ingressCapacity))
                     }
                 }
               }.tapError {
                 case error: ConnectionError.IngressSaturated => terminate(error)
                 case _                                       => ZIO.unit
               }
      yield response
    }

  def awaitFailure: UIO[ConnectionError] = failure.await

  private[scalive] def abort(error: ConnectionError): UIO[Unit] = terminate(error)

  private[scalive] def pollFailure: UIO[Option[ConnectionError]] =
    failure.poll.flatMap(ZIO.foreach(_)(identity))

  private[scalive] def awaitClosed: UIO[Unit] = closed.await

  private[connection] def inspectModel: IO[ConnectionError, Model] =
    kernel.inspect.map(_.model.model).mapError(connectionError)

  private[scalive] def inspectTree: IO[ConnectionError, EvaluatedTree] =
    kernel.inspect.map(_.render.tree).mapError(connectionError)

  private[connection] def inspectFlash: IO[ConnectionError, Map[FlashKind, String]] =
    kernel.inspect.map(_.model.flash).mapError(connectionError)

  private[connection] def inspectComponentIds: IO[ConnectionError, Vector[ComponentInstanceId]] =
    kernel.inspect.map(_.components.values.map(_.id)).mapError(connectionError)

  private[connection] def inspectComponentModel[A](
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[A]] =
    kernel.inspect
      .map(_.components.get(component).map(_.model.asInstanceOf[A])).mapError(connectionError)

  private[connection] def inspectComponentProps[A](
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[A]] =
    kernel.inspect
      .map(_.components.get(component).map(_.props.asInstanceOf[A])).mapError(connectionError)

  private[connection] def inspectComponentTree(
    component: ComponentInstanceId
  ): IO[ConnectionError, Option[EvaluatedTree]] =
    kernel.inspect.map(_.components.get(component).map(_.render.tree)).mapError(connectionError)

  private[scalive] def componentForToken(
    token: Object
  ): IO[ConnectionError, Option[ComponentInstanceId]] =
    kernel.inspect
      .map(
        _.components.values.find(component => component.ref.asInstanceOf[AnyRef] eq token).map(_.id)
      ).mapError(connectionError)

  private[connection] def componentWasClosed(component: ComponentInstanceId): UIO[Boolean] =
    componentEnvironment.wasClosed(component)

  private[connection] def componentWasDiscarded(component: ComponentInstanceId): UIO[Boolean] =
    componentEnvironment.wasDiscarded(component)

  private[connection] def submitInfo(message: Msg): IO[ConnectionError, Unit] =
    kernel.submit(SessionCommand.Message(epoch, message)).unit.mapError(connectionError)

  private[connection] def submitAsyncCompletion(event: LiveAsyncEvent[Msg])
    : IO[ConnectionError, Unit] =
    kernel.submit(SessionCommand.AsyncCompletion(epoch, event)).unit.mapError(connectionError)

  private[connection] def ingressDepth: UIO[Int] = ingress.size

  private[connection] def pendingCount: UIO[Int] = pending.get.map(_.size)

  private[connection] def isClosing: UIO[Boolean] = closing.isDone

  /** Control-plane shutdown; it never waits for space in an application queue. */
  def close: UIO[Unit] =
    ZIO.uninterruptibleMask { restore =>
      closing.succeed(()).flatMap {
        case false => restore(closed.await)
        case true  =>
          for
            _ <- ingressGate.withPermit {
                   for
                     queued <- ingress.takeAll
                     _      <- ZIO.foreachDiscard(queued)(event =>
                            complete(event.id, Left(ConnectionError.Closed))
                          )
                     _ <- ingress.shutdown
                   yield ()
                 }
            _ <- kernel.close
            _ <- outbound.shutdown
            _ <- writer.close
            _ <- failPending(ConnectionError.Closed)
            _ <- closed.succeed(())
          yield ()
      }
    }

  private def runIngress: URIO[Scope, Unit] =
    ingress.take
      .flatMap { event =>
        val command                    = event.id
        val input: SessionCommand[Msg] = event match
          case Event.Browser(_, binding, payload, name, rawJson) =>
            SessionCommand.ClientEvent(epoch, binding, payload, name, rawJson)
          case Event.ComponentBrowser(_, component, binding, payload, name, rawJson) =>
            SessionCommand.ComponentClientEvent(
              epoch,
              component,
              binding,
              payload,
              name,
              rawJson
            )
          case Event.ComponentMessage(_, component, message) =>
            SessionCommand.ComponentMessage(epoch, component, message)
          case Event.ComponentAsync(_, component, event) =>
            SessionCommand.ComponentAsyncCompletion(
              epoch,
              component,
              event.asInstanceOf[LiveAsyncEvent[Any]]
            )
          case Event.ComponentUpdate(_, component) =>
            SessionCommand.ComponentUpdate(epoch, component)
          case Event.Patch(_, destination) => SessionCommand.ParamsPatch(epoch, destination)
        kernel
          .enqueue(command, input).foldZIO(
            rejection => reject(command, rejection),
            await =>
              await
                .foldZIO(rejection => reject(command, rejection), _ => awaitCompletion(command))
                .forkScoped.unit
          ) *> runIngress
      }.catchAllCause(_ => ZIO.unit)

  private def reject(command: CommandId, rejection: SessionRejection): UIO[Unit] =
    closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false =>
        rejection match
          case _: SessionRejection.UnknownBinding | _: SessionRejection.BindingFailed |
              _: SessionRejection.UnknownComponent | _: SessionRejection.StaleComponent |
              SessionRejection.UnknownComponentTarget | _: SessionRejection.AmbiguousComponent =>
            awaitEarlierCommands(command) *> writer
              .send(ConnectionOutput.Rejected(command, rejection)).foldZIO(
                error => completeAfterWriterClose(command, error),
                _ => complete(command, Right(()))
              )
          case SessionRejection.SessionFailed(sessionFailure) =>
            terminate(ConnectionError.SessionFailed(sessionFailure))
          case _: SessionRejection.Terminal =>
            complete(command, Left(ConnectionError.Closed))
          case other => terminate(ConnectionError.KernelRejected(other))
    }

  private def awaitEarlierCommands(command: CommandId): UIO[Unit] =
    pending.get.flatMap { current =>
      current.get(command) match
        case None                 => ZIO.unit
        case Some(currentCommand) =>
          ZIO.foreachDiscard(
            current.valuesIterator.filter(_.sequence < currentCommand.sequence).toVector
          )(_.response.await.ignore)
    }

  private def runOutbound(first: Boolean): UIO[Unit] =
    outbound.take.foldZIO(
      error =>
        closing.isDone.flatMap {
          case true  => ZIO.unit
          case false => terminate(ConnectionError.OutboundFailed(error.toString))
        },
      batch => writeBatch(batch.items, first).flatMap(runOutbound)
    )

  private def writeBatch(outputs: Vector[SessionOutput], first: Boolean): UIO[Boolean] =
    outputs.foldLeft[UIO[Boolean]](ZIO.succeed(first)) { (state, output) =>
      state.flatMap { isFirst =>
        pending.get.flatMap { pendingCommands =>
          val correlatedCommand = output.command.filter(pendingCommands.contains)
          val connectionOutput  =
            if isFirst then
              output.navigation.fold[ConnectionOutput](
                ConnectionOutput.Joined(output.delta, output.effects)
              )(
                ConnectionOutput.JoinedNavigation(output.delta, _, output.effects)
              )
            else
              (correlatedCommand, output.navigation) match
                case (Some(command), Some(navigation)) =>
                  ConnectionOutput.ReplyNavigation(
                    command,
                    output.delta,
                    navigation,
                    output.effects
                  )
                case (Some(command), None) =>
                  ConnectionOutput.Reply(command, output.delta, output.effects)
                case (None, Some(navigation)) =>
                  ConnectionOutput.DiffNavigation(output.delta, navigation, output.effects)
                case (None, None) => ConnectionOutput.Diff(output.delta, output.effects)

          val ordered = connectionOutput match
            case ConnectionOutput.Reply(command, _, _)              => awaitEarlierCommands(command)
            case ConnectionOutput.ReplyNavigation(command, _, _, _) => awaitEarlierCommands(command)
            case _                                                  => ZIO.unit

          ordered *> writer
            .send(connectionOutput).foldZIO(
              error =>
                closing.isDone
                  .flatMap {
                    case false => terminate(writerFailure(error))
                    case true  =>
                      connectionOutput match
                        case ConnectionOutput.Reply(command, _, _) =>
                          complete(command, Left(ConnectionError.Closed))
                        case ConnectionOutput.ReplyNavigation(command, _, _, _) =>
                          complete(command, Left(ConnectionError.Closed))
                        case ConnectionOutput.Rejected(command, _) =>
                          complete(command, Left(ConnectionError.Closed))
                        case ConnectionOutput.Joined(_, _) |
                            ConnectionOutput.JoinedNavigation(_, _, _) =>
                          bootstrapReady.fail(ConnectionError.Closed).unit
                        case ConnectionOutput.Diff(_, _) |
                            ConnectionOutput.DiffNavigation(_, _, _) =>
                          ZIO.unit
                  }.as(false),
              _ =>
                val signal = connectionOutput match
                  case ConnectionOutput.Joined(_, _) | ConnectionOutput.JoinedNavigation(_, _, _) =>
                    bootstrapReady.succeed(()).unit
                  case ConnectionOutput.Reply(command, _, _) => complete(command, Right(()))
                  case ConnectionOutput.ReplyNavigation(command, _, _, _) =>
                    complete(command, Right(()))
                  case ConnectionOutput.Rejected(command, _) => complete(command, Right(()))
                  case ConnectionOutput.Diff(_, _) | ConnectionOutput.DiffNavigation(_, _, _) =>
                    ZIO.unit
                val finish = connectionOutput match
                  case ConnectionOutput.JoinedNavigation(_, navigation, _)
                      if !navigation.kind.isPatch =>
                    close
                  case ConnectionOutput.ReplyNavigation(_, _, navigation, _)
                      if !navigation.kind.isPatch =>
                    close
                  case ConnectionOutput.DiffNavigation(_, navigation, _)
                      if !navigation.kind.isPatch =>
                    close
                  case _ => ZIO.unit
                (signal *> finish).as(false)
            )
        }
      }
    }

  private def connectionError(rejection: SessionRejection): ConnectionError = rejection match
    case SessionRejection.SessionFailed(value) => ConnectionError.SessionFailed(value)
    case _: SessionRejection.Terminal          => ConnectionError.Closed
    case other                                 => ConnectionError.KernelRejected(other)

  private def monitorKernel: UIO[Unit] =
    kernel.awaitTermination.flatMap {
      case SessionState.Crashed(_, sessionFailure) =>
        terminate(ConnectionError.SessionFailed(sessionFailure))
      case _ => ZIO.unit
    }

  private def terminate(error: ConnectionError): UIO[Unit] =
    failure.succeed(error).flatMap {
      case true =>
        bootstrapReady.fail(error).unit *> failPending(error) *> close
      case false => ZIO.unit
    }

  private def complete(command: CommandId, result: Either[ConnectionError, Unit]): UIO[Unit] =
    pending.get.flatMap(_.get(command) match
      case Some(pendingCommand) =>
        pendingCommand.response.done(Exit.fromEither(result)).unit *> pending.update(_ - command)
      case None => ZIO.unit)

  private def failPending(error: ConnectionError): UIO[Unit] =
    pending
      .getAndSet(Map.empty).flatMap(values =>
        ZIO.foreachDiscard(values.values)(_.response.fail(error).unit)
      )

  private def awaitCompletion(command: CommandId): UIO[Unit] =
    pending.get.flatMap(
      _.get(command).fold[UIO[Unit]](ZIO.unit)(_.response.await.ignore)
    )

  private def writerFailure(error: SerialWriter.Error): ConnectionError = error match
    case SerialWriter.Error.WriteFailed(cause) => ConnectionError.SinkFailed(cause)
    case other                                 => ConnectionError.OutboundFailed(other.toString)

  private def completeAfterWriterClose(
    command: CommandId,
    error: SerialWriter.Error
  ): UIO[Unit] =
    closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false => terminate(writerFailure(error))
    }
end RootConnection

private[scalive] object RootConnection:
  final private case class PendingCommand(
    sequence: Long,
    response: Promise[ConnectionError, Unit])

  private enum Event:
    case Browser(
      command: CommandId,
      binding: BindingId,
      payload: BindingPayload,
      eventName: Option[String],
      rawJson: Option[String])
    case ComponentBrowser(
      command: CommandId,
      component: ComponentInstanceId,
      binding: BindingId,
      payload: BindingPayload,
      eventName: Option[String],
      rawJson: Option[String])
    case ComponentMessage(command: CommandId, component: ComponentInstanceId, message: Any)
    case ComponentAsync(
      command: CommandId,
      component: ComponentInstanceId,
      event: LiveAsyncEvent[?])
    case ComponentUpdate(command: CommandId, component: ComponentInstanceId)
    case Patch(command: CommandId, destination: URL)

    def id: CommandId = this match
      case Browser(command, _, _, _, _)             => command
      case ComponentBrowser(command, _, _, _, _, _) => command
      case ComponentMessage(command, _, _)          => command
      case ComponentAsync(command, _, _)            => command
      case ComponentUpdate(command, _)              => command
      case Patch(command, _)                        => command

  def start[Msg, Model](
    config: ConnectionConfig,
    metadata: RootConnectionMetadata,
    liveView: LiveView[Msg, Model],
    sink: ConnectionOutput => Task[Unit],
    topologyPreparer: NestedTopologyPreparer = NestedTopologyPreparer.unavailable,
    ownsPageTitle: Boolean = true,
    requestedLifecycle: Option[LifecycleId] = None
  ): ZIO[Scope, ConnectionError, RootConnection[Msg, Model]] =
    startLifecycle(
      config,
      metadata,
      RootLifecycle.ordinary(liveView),
      sink,
      topologyPreparer,
      ownsPageTitle,
      requestedLifecycle
    )

  def startLifecycle[Msg, Model](
    config: ConnectionConfig,
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    sink: ConnectionOutput => Task[Unit],
    topologyPreparer: NestedTopologyPreparer = NestedTopologyPreparer.unavailable,
    ownsPageTitle: Boolean = true,
    requestedLifecycle: Option[LifecycleId] = None
  ): ZIO[Scope, ConnectionError, RootConnection[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        lifecycleId <- requestedLifecycle.fold(
                         ZIO
                           .fromEither(LifecycleId.fresh()).mapError(error =>
                             ConnectionError.SessionFailed(
                               SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                             )
                           )
                       )(ZIO.succeed(_))
        rootOwner = OwnerId.Root(lifecycleId)
        program <- ZIO.acquireRelease(
                     ZIO
                       .fromEither(
                         RenderProgram.compile[RootState[Msg, Model], Msg](
                           signal => lifecycle.view(signal.map(state => state.model -> state.url)),
                           _.flash
                         )
                       )
                       .mapError(ConnectionError.RenderCompilationFailed.apply)
                   )(_.close)
        sessionConfig <- ZIO
                           .fromEither(
                             SessionConfig.make(
                               config.kernelMailboxCapacity,
                               config.continuationCapacity
                             )
                           ).mapError(error => ConnectionError.OutboundFailed(error.toString))
        outbound <- InMemoryOutboundReservations
                      .make[SessionOutput](config.outboundReservationCapacity)
                      .mapError(error => ConnectionError.OutboundFailed(error.toString))
        writer <- SerialWriter
                    .make[ConnectionOutput](config.writerCapacity)(sink)
                    .mapError(error => ConnectionError.OutboundFailed(error.toString))
        componentEnvironment <- ConnectedComponentEnvironment.make[Msg, Model](
                                  metadata,
                                  lifecycleId
                                )
        initialHooks = RootHookRegistry.fromStatic(lifecycle.hooks)
        logic        = SessionLogic[Msg, RootState[Msg, Model]](
                  bootstrap =
                    for
                      journal <-
                        RootTurnJournal.make(rootOwner, initialHooks, metadata.initialFlash)
                      mountContext = RootMountContext.connected[Msg, Model](
                                       metadata,
                                       lifecycle.initialUrl,
                                       journal
                                     )
                      mounted         <- ZIO.suspend(lifecycle.mount(mountContext))
                      mountNavigation <- journal.navigation.get
                      model           <- mountNavigation match
                                 case Some(_) => ZIO.succeed(mounted)
                                 case None    =>
                                   val paramsContext = RootParamsContext[Msg, Model](
                                     metadata,
                                     lifecycle.initialUrl,
                                     journal,
                                     connected = true
                                   )
                                   for
                                     prepared <- lifecycle.prepareParams(lifecycle.initialUrl)
                                     registry <- journal.hookRegistry[Msg, Model]
                                     hooked   <-
                                       if prepared.runHooks then
                                         runParamsHooks(
                                           registry,
                                           mounted,
                                           lifecycle.initialUrl,
                                           paramsContext
                                         )
                                       else ZIO.succeed(Hooked.Continue(mounted))
                                     result <- hooked match
                                                 case Hooked.Halt(value)     => ZIO.succeed(value)
                                                 case Hooked.Continue(value) =>
                                                   ZIO.suspend(prepared.run(value, paramsContext))
                                   yield result
                      navigation         <- journal.navigationWithFlash
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                      streams            <- journal.streamSnapshot
                      pageTitle = lifecycle.pageTitle(model)
                    yield TurnDraft(
                      RootState(model, lifecycle.initialUrl, hooks, flash, pageTitle, streams),
                      url = Some(lifecycle.initialUrl),
                      navigation = navigation,
                      effects =
                        SessionEffects(Option.when(ownsPageTitle)(pageTitle).flatten, clientEvents),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations
                    ),
                  handle = (state, message) =>
                    for
                      journal <- RootTurnJournal.make(
                                   rootOwner,
                                   state.hooks,
                                   state.flash,
                                   initialStreams = state.streams
                                 )
                      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
                      model <- ZIO.suspend(lifecycle.handleMessage(state.model, context, message))
                      navigation         <- journal.navigationWithFlash
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                      streams            <- journal.streamSnapshot
                      pageTitle = lifecycle.pageTitle(model)
                    yield TurnDraft(
                      RootState(model, state.url, hooks, flash, pageTitle, streams),
                      url = Some(state.url),
                      navigation = navigation,
                      effects = SessionEffects(
                        Option.when(ownsPageTitle)(titleChange(state.pageTitle, pageTitle)).flatten,
                        clientEvents
                      ),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations
                    ),
                  handleEvent = Some((state, message) =>
                    runMessageTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      message,
                      _.event
                    )
                  ),
                  handleInfo = Some((state, message) =>
                    runMessageTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      message,
                      _.info
                    )
                  ),
                  handleAsync = Some((state, event) =>
                    runAsyncTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      event,
                      None
                    )
                  ),
                  handleManagedAsync = Some((state, event, message) =>
                    runAsyncTurn(
                      state,
                      metadata,
                      lifecycle,
                      rootOwner,
                      event,
                      Some(message)
                    )
                  ),
                  interceptClientEvent = (state, event) =>
                    (event.eventName, event.rawJson) match
                      case (Some(name), Some(raw)) =>
                        val matching = state.hooks.browser.filter(_.name == name)
                        if matching.isEmpty then ZIO.none
                        else
                          for
                            journal <- RootTurnJournal.make(
                                         rootOwner,
                                         state.hooks,
                                         state.flash,
                                         initialStreams = state.streams
                                       )
                            context = RootMessageContext[Msg, Model](metadata, state.url, journal)
                            model            <- runBrowserHooks(matching, state.model, raw, context)
                            navigation       <- journal.navigationWithFlash
                            hooks            <- journal.hookRegistry[Msg, Model]
                            flash            <- journal.flash.get
                            clientEvents     <- journal.clientEvents.get
                            componentUpdates <- journal.componentUpdates.get
                            resourceOperations <- journal.resourceOperationSnapshot
                            streams            <- journal.streamSnapshot
                            pageTitle = lifecycle.pageTitle(model)
                          yield Some(
                            TurnDraft(
                              RootState(model, state.url, hooks, flash, pageTitle, streams),
                              url = Some(state.url),
                              navigation = navigation,
                              effects = SessionEffects(
                                Option
                                  .when(ownsPageTitle)(
                                    titleChange(state.pageTitle, pageTitle)
                                  ).flatten,
                                clientEvents
                              ),
                              componentUpdates = componentUpdates,
                              resourceOperations = resourceOperations
                            )
                          )
                        end if
                      case _ => ZIO.none,
                  handleParams = (state, destination) =>
                    for
                      journal <- RootTurnJournal.make(
                                   rootOwner,
                                   state.hooks,
                                   state.flash,
                                   initialStreams = state.streams
                                 )
                      context = RootParamsContext[Msg, Model](
                                  metadata,
                                  destination,
                                  journal,
                                  connected = true
                                )
                      prepared <- lifecycle.prepareParams(destination)
                      hooked   <-
                        if prepared.runHooks then
                          runParamsHooks(state.hooks, state.model, destination, context)
                        else ZIO.succeed(Hooked.Continue(state.model))
                      model <- hooked match
                                 case Hooked.Halt(value)     => ZIO.succeed(value)
                                 case Hooked.Continue(value) =>
                                   ZIO.suspend(prepared.run(value, context))
                      navigation         <- journal.navigationWithFlash
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                      streams            <- journal.streamSnapshot
                      pageTitle = lifecycle.pageTitle(model)
                    yield TurnDraft(
                      RootState(model, destination, hooks, flash, pageTitle, streams),
                      url = Some(destination),
                      navigation = navigation,
                      effects = SessionEffects(
                        Option.when(ownsPageTitle)(titleChange(state.pageTitle, pageTitle)).flatten,
                        clientEvents
                      ),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations
                    ),
                  afterRender = draft =>
                    for
                      journal <- RootTurnJournal.make(
                                   rootOwner,
                                   draft.model.hooks,
                                   draft.model.flash,
                                   draft.effects.clientEvents,
                                   draft.componentUpdates,
                                   draft.navigation,
                                   draft.resourceOperations,
                                   draft.model.streams
                                 )
                      context = RootAfterRenderContext[Msg, Model](metadata, journal)
                      _ <- ZIO.foreachDiscard(draft.model.hooks.afterRender)(
                             _.invoke(draft.model.model, context)
                           )
                      hooks              <- journal.hookRegistry[Msg, Model]
                      flash              <- journal.flash.get
                      clientEvents       <- journal.clientEvents.get
                      componentUpdates   <- journal.componentUpdates.get
                      resourceOperations <- journal.resourceOperationSnapshot
                    yield draft.copy(
                      model = draft.model.copy(hooks = hooks, flash = flash),
                      effects = draft.effects.copy(clientEvents = clientEvents),
                      componentUpdates = componentUpdates,
                      resourceOperations = resourceOperations
                    ),
                  validateStreams =
                    (state, requirements) => ZIO.attempt(state.streams.validate(requirements))
                )
        kernel <- SessionKernel
                    .start(
                      sessionConfig,
                      logic,
                      program,
                      outbound,
                      componentEnvironment,
                      Some(lifecycleId),
                      topologyPreparer
                    )
                    .mapError(ConnectionError.SessionFailed.apply)
        ingress        <- Queue.dropping[Event](config.ingressCapacity)
        ingressGate    <- Semaphore.make(1L)
        pending        <- Ref.make(Map.empty[CommandId, PendingCommand])
        failure        <- Promise.make[Nothing, ConnectionError]
        bootstrapReady <- Promise.make[ConnectionError, Unit]
        closing        <- Promise.make[Nothing, Unit]
        closed         <- Promise.make[Nothing, Unit]
        connection = RootConnection(
                       kernel.lifecycle,
                       kernel.epoch,
                       config,
                       kernel,
                       componentEnvironment,
                       outbound,
                       writer,
                       ingress,
                       ingressGate,
                       pending,
                       failure,
                       bootstrapReady,
                       closing,
                       closed
                     )
        _ <- connection.runOutbound(first = true).interruptible.forkScoped
        _ <- connection.runIngress.interruptible.forkScoped
        _ <- connection.monitorKernel.interruptible.forkScoped
        _ <- ZIO.addFinalizer(connection.close)
        _ <- restore(bootstrapReady.await).onError(_ => connection.close)
      yield connection
    }

  private enum Hooked[+A]:
    case Continue(value: A)
    case Halt(value: A)

  private def titleChange(
    previous: Option[String],
    current: Option[String]
  ): Option[String] = Option.when(previous != current)(current.getOrElse(""))

  private def runMessageTurn[Msg, Model](
    state: RootState[Msg, Model],
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    message: Msg,
    select: RootHookRegistry[Msg, Model] => Vector[RootHookRegistry.Event[Msg, Model]]
  ): Task[TurnDraft[Msg, RootState[Msg, Model]]] =
    for
      journal <- RootTurnJournal.make(
                   owner,
                   state.hooks,
                   state.flash,
                   initialStreams = state.streams
                 )
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      hooked <- runEventHooks(select(state.hooks), state.model, message, context)
      model  <- hooked match
                 case Hooked.Halt(value)     => ZIO.succeed(value)
                 case Hooked.Continue(value) =>
                   ZIO.suspend(
                     lifecycle.handleMessage(value, context, message)
                   )
      navigation         <- journal.navigationWithFlash
      hooks              <- journal.hookRegistry[Msg, Model]
      flash              <- journal.flash.get
      clientEvents       <- journal.clientEvents.get
      componentUpdates   <- journal.componentUpdates.get
      resourceOperations <- journal.resourceOperationSnapshot
      streams            <- journal.streamSnapshot
      pageTitle = lifecycle.pageTitle(model)
    yield TurnDraft(
      RootState(model, state.url, hooks, flash, pageTitle, streams),
      url = Some(state.url),
      navigation = navigation,
      effects = SessionEffects(titleChange(state.pageTitle, pageTitle), clientEvents),
      componentUpdates = componentUpdates,
      resourceOperations = resourceOperations
    )

  private def runEventHooks[Msg, Model](
    hooks: Vector[RootHookRegistry.Event[Msg, Model]],
    initial: Model,
    message: Msg,
    context: MessageContext[Msg, Model]
  ): LiveIO[Hooked[Model]] =
    hooks.foldLeft[LiveIO[Hooked[Model]]](ZIO.succeed(Hooked.Continue(initial))) { (effect, hook) =>
      effect.flatMap {
        case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
        case Hooked.Continue(model)     =>
          hook.invoke(model, message, context).map {
            case LiveHookResult.Continue(next) => Hooked.Continue(next)
            case LiveHookResult.Halt(next)     => Hooked.Halt(next)
          }
      }
    }

  private def runParamsHooks[Msg, Model](
    registry: RootHookRegistry[Msg, Model],
    initial: Model,
    url: URL,
    context: ParamsContext[Msg, Model]
  ): LiveIO[Hooked[Model]] =
    registry.params.foldLeft[LiveIO[Hooked[Model]]](ZIO.succeed(Hooked.Continue(initial))) {
      (effect, hook) =>
        effect.flatMap {
          case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
          case Hooked.Continue(model)     =>
            hook.invoke(model, url, context).map {
              case LiveHookResult.Continue(next) => Hooked.Continue(next)
              case LiveHookResult.Halt(next)     => Hooked.Halt(next)
            }
        }
    }

  private def runAsyncTurn[Msg, Model](
    state: RootState[Msg, Model],
    metadata: RootConnectionMetadata,
    lifecycle: RootLifecycle[Msg, Model],
    owner: OwnerId,
    event: LiveAsyncEvent[Msg],
    mappedMessage: Option[Msg]
  ): Task[TurnDraft[Msg, RootState[Msg, Model]]] =
    for
      journal <- RootTurnJournal.make(
                   owner,
                   state.hooks,
                   state.flash,
                   initialStreams = state.streams
                 )
      context = RootMessageContext[Msg, Model](metadata, state.url, journal)
      hooked <- state.hooks.async.foldLeft[LiveIO[Hooked[Model]]](
                  ZIO.succeed(Hooked.Continue(state.model))
                ) { (effect, hook) =>
                  effect.flatMap {
                    case halted: Hooked.Halt[Model] => ZIO.succeed(halted)
                    case Hooked.Continue(model)     =>
                      hook.invoke(model, event, context).map {
                        case LiveHookResult.Continue(next) => Hooked.Continue(next)
                        case LiveHookResult.Halt(next)     => Hooked.Halt(next)
                      }
                  }
                }
      model <- hooked match
                 case Hooked.Halt(value)     => ZIO.succeed(value)
                 case Hooked.Continue(value) =>
                   mappedMessage.orElse(
                     event.result match
                       case LiveAsyncResult.Succeeded(message) => Some(message)
                       case _                                  => None
                   ) match
                     case Some(message) =>
                       ZIO.suspend(lifecycle.handleMessage(value, context, message))
                     case None => ZIO.succeed(value)
      navigation         <- journal.navigationWithFlash
      hooks              <- journal.hookRegistry[Msg, Model]
      flash              <- journal.flash.get
      clientEvents       <- journal.clientEvents.get
      componentUpdates   <- journal.componentUpdates.get
      resourceOperations <- journal.resourceOperationSnapshot
      streams            <- journal.streamSnapshot
      pageTitle = lifecycle.pageTitle(model)
    yield TurnDraft(
      RootState(model, state.url, hooks, flash, pageTitle, streams),
      url = Some(state.url),
      navigation = navigation,
      effects = SessionEffects(titleChange(state.pageTitle, pageTitle), clientEvents),
      componentUpdates = componentUpdates,
      resourceOperations = resourceOperations
    )

  private def runBrowserHooks[Msg, Model](
    hooks: Vector[RootHookRegistry.Browser[Msg, Model]],
    committedModel: Model,
    raw: String,
    context: MessageContext[Msg, Model]
  ): LiveIO[Model] =
    hooks
      .foldLeft[LiveIO[Either[Unit, Model]]](ZIO.succeed(Right(committedModel))) { (effect, hook) =>
        effect.flatMap {
          case malformed @ Left(_) => ZIO.succeed(malformed)
          case Right(model)        =>
            hook.invoke(model, raw, context) match
              case Right(next) => next.map(Right(_))
              case Left(error) =>
                ZIO.logWarning(
                  s"root browser event '${hook.name}' payload was malformed: $error"
                ) *>
                  ZIO.succeed(Left(()))
        }
      }.map(_.fold(_ => committedModel, identity))
end RootConnection
