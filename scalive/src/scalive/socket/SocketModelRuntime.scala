package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketModelRuntime:
  def applyHookHalt[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    reply: Option[Json],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      diff <- updateModelAndSubscriptions(modelVar, el, hookModel, state)
      payload = hookReplyPayload(reply, diff)
      _ <- publishPayload(payload, meta, state)
    yield ()

  def applyBoundEvent[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    hookModel: Model,
    event: Payload.Event,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    el.findBinding(event.event) match
      case Some(binding) =>
        for
          updatedModel <- normalize(state.lv.update(hookModel)(binding(event.params)), state.ctx)
          diff         <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
          _            <- publishPayload(Payload.okReply(LiveResponse.Diff(diff)), meta, state)
        yield ()
      case None =>
        ZIO.logWarning(s"Ignoring unknown binding ID ${event.event}") *>
          publishPayload(Payload.okReply(LiveResponse.Empty), meta, state)

  def updateModelAndSubscriptions[Msg, Model](
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

  def publishPayload[Msg, Model](
    payload: Payload,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): UIO[Unit] =
    state.outHub.publish(payload -> meta).unit

  private def hookReplyPayload(
    reply: Option[Json],
    diff: Diff
  ): Payload =
    reply match
      case Some(replyValue) =>
        Payload.okReply(LiveResponse.HookReply(replyValue, Option.when(!diff.isEmpty)(diff)))
      case None if !diff.isEmpty =>
        Payload.okReply(LiveResponse.Diff(diff))
      case None =>
        Payload.okReply(LiveResponse.Empty)
end SocketModelRuntime
