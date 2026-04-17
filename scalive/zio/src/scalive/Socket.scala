package scalive

import java.net.URI

import zio.*
import zio.Queue
import zio.http.QueryParams
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

final case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  livePatch: (String, WebSocketMessage.Meta) => Task[Unit],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit])

object Socket:

  final private case class RuntimeState[Msg, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta,
    inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
    outHub: Hub[(Payload, WebSocketMessage.Meta)],
    ref: Ref[(Var[Model], HtmlElement)],
    lvStreamRef: SubscriptionRef[ZStream[Any, Nothing, Msg]],
    patchRedirectCountRef: Ref[Int],
    initDiff: Diff)

  def start[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Socket[Msg, Model]] =
    ZIO.logAnnotate("lv", id) {
      for
        state       <- initializeRuntime(lv, ctx, meta)
        clientFiber <- startClientFiber(state)
        serverFiber <- startServerFiber(state)
        livePatch =
          (url: String, patchMeta: WebSocketMessage.Meta) => handleLivePatch(url, patchMeta, state)
        outbox = buildOutbox(state)
        stop   = buildShutdown(state, clientFiber, serverFiber)
      yield Socket[Msg, Model](id, token, state.inbox, livePatch, outbox, stop)
    }

  private def initializeRuntime[Msg, Model](
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): Task[RuntimeState[Msg, Model]] =
    for
      inbox     <- Queue.bounded[(Payload.Event, WebSocketMessage.Meta)](4)
      outHub    <- Hub.unbounded[(Payload, WebSocketMessage.Meta)]
      initModel <- normalize(lv.init, ctx)
      modelVar = Var(initModel)
      el       = lv.view(modelVar)
      ref <- Ref.make((modelVar, el))
      initDiff = el.diff(trackUpdates = false)
      lvStreamRef <-
        SubscriptionRef.make(lv.subscriptions(initModel).provideLayer(ZLayer.succeed(ctx)))
      patchRedirectCountRef <- Ref.make(0)
    yield RuntimeState(
      lv = lv,
      ctx = ctx,
      meta = meta,
      inbox = inbox,
      outHub = outHub,
      ref = ref,
      lvStreamRef = lvStreamRef,
      patchRedirectCountRef = patchRedirectCountRef,
      initDiff = initDiff
    )

  private def startClientFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    ZStream
      .fromQueue(state.inbox)
      .runForeach((event, meta) => handleClientEvent(event, meta, state))
      .fork

  private def handleClientEvent[Msg, Model](
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      hookResult     <- normalize(
                      state.lv.handleHook(modelVar.currentValue, event.event, event.value),
                      state.ctx
                    )
      _ <- hookResult match
             case HookResult.Halt(hookModel, reply) =>
               applyHookHalt(modelVar, el, hookModel, reply, meta, state)
             case HookResult.Continue(hookModel) =>
               applyBoundEvent(modelVar, el, hookModel, event, meta, state)
    yield ()

  private def handleLivePatch[Msg, Model](
    url: String,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      parsedUri      <- ZIO.attempt(URI.create(url))
      params = QueryParams
                 .decode(Option(parsedUri.getRawQuery).getOrElse(""))
                 .map
                 .iterator
                 .map { case (key, values) =>
                   key -> values.lastOption.getOrElse("")
                 }
                 .toMap
      result <-
        normalize(state.lv.handleParams(modelVar.currentValue, params, parsedUri), state.ctx)
      _ <- result match
             case ParamsResult.Continue(model) =>
               for
                 _    <- state.patchRedirectCountRef.set(0)
                 diff <- updateModelAndSubscriptions(modelVar, el, model, state)
                 _    <- ZIO.when(!diff.isEmpty)(
                        publishPayload(Payload.Diff(diff), meta.copy(messageRef = None), state)
                      )
               yield ()
             case ParamsResult.PushPatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Push,
                 meta,
                 state
               )
             case ParamsResult.ReplacePatch(model, to) =>
               handleLivePatchRedirect(
                 modelVar,
                 el,
                 model,
                 to,
                 LivePatchKind.Replace,
                 meta,
                 state
               )
    yield ()

  private def handleLivePatchRedirect[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    to: String,
    kind: LivePatchKind,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      redirectCount <- state.patchRedirectCountRef.updateAndGet(_ + 1)
      _             <- updateModelAndSubscriptions(modelVar, el, model, state)
      _             <-
        if redirectCount > 20 then
          publishPayload(Payload.Error, meta.copy(messageRef = None), state)
        else
          publishPayload(
            Payload.LiveNavigation(to, kind),
            meta.copy(messageRef = None),
            state
          ) *> handleLivePatch(to, meta, state)
    yield ()

  private def applyHookHalt[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    reply: Option[zio.json.ast.Json],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      diff <- updateModelAndSubscriptions(modelVar, el, hookModel, state)
      payload = hookReplyPayload(reply, diff)
      _ <- publishPayload(payload, meta, state)
    yield ()

  private def applyBoundEvent[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      binding <-
        ZIO
          .succeed(el.findBinding(event.event))
          .someOrFail(
            new IllegalArgumentException(
              s"No binding found for event ID ${event.event}"
            )
          )
      updatedModel <- normalize(state.lv.update(hookModel)(binding(event.params)), state.ctx)
      diff         <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
      _            <- publishPayload(Payload.okReply(LiveResponse.Diff(diff)), meta, state)
    yield ()

  private def updateModelAndSubscriptions[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    model: Model,
    state: RuntimeState[Msg, Model]
  ): Task[Diff] =
    for
      _ = modelVar.set(model)
      _ <- state.lvStreamRef.set(
             state.lv.subscriptions(model).provideLayer(ZLayer.succeed(state.ctx))
           )
      diff = el.diff()
    yield diff

  private def hookReplyPayload(
    reply: Option[zio.json.ast.Json],
    diff: Diff
  ): Payload =
    reply match
      case Some(replyValue) =>
        Payload.okReply(LiveResponse.HookReply(replyValue, Option.when(!diff.isEmpty)(diff)))
      case None if !diff.isEmpty =>
        Payload.okReply(LiveResponse.Diff(diff))
      case None =>
        Payload.okReply(LiveResponse.Empty)

  private def startServerFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    serverMsgStream(state).runForeach((msg, meta) => handleServerMsg(msg, meta, state)).fork

  private def serverMsgStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Msg, WebSocketMessage.Meta)] =
    (ZStream.fromZIO(state.lvStreamRef.get) ++ state.lvStreamRef.changes)
      .flatMapParSwitch(1, 1)(identity)
      .map(_ -> state.meta.copy(messageRef = None, eventType = "diff"))

  private def handleServerMsg[Msg, Model](
    msg: Msg,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (modelVar, el) <- state.ref.get
      updatedModel   <- normalize(state.lv.update(modelVar.currentValue)(msg), state.ctx)
      diff           <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
      _              <- publishPayload(Payload.Diff(diff), meta, state)
    yield ()

  private def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    state.outHub.publish(payload -> meta).unit

  private def buildOutbox[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)] =
    ZStream.succeed(
      Payload.okReply(LiveResponse.InitDiff(state.initDiff)) -> state.meta
    ) ++ ZStream
      .unwrapScoped(ZStream.fromHubScoped(state.outHub)).filterNot {
        case (Payload.Diff(diff), _) => diff.isEmpty
        case _                       => false
      }

  private def buildShutdown[Msg, Model](
    state: RuntimeState[Msg, Model],
    clientFiber: Fiber.Runtime[Throwable, Unit],
    serverFiber: Fiber.Runtime[Throwable, Unit]
  ): UIO[Unit] =
    state.outHub.publish(Payload.Close -> state.meta) *>
      state.inbox.shutdown *>
      state.outHub.shutdown *>
      clientFiber.interrupt.unit *>
      serverFiber.interrupt.unit
end Socket
