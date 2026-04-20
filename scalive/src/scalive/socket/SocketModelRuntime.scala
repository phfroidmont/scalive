package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketModelRuntime:
  def captureNavigation[Msg, Model, A](
    state: RuntimeState[Msg, Model],
    initial: Option[LiveNavigationCommand] = None
  )(
    effect: Task[A]
  ): Task[(A, Option[LiveNavigationCommand])] =
    for
      _          <- state.navigationRef.set(initial)
      exit       <- effect.exit
      navigation <- state.navigationRef.getAndSet(None)
      value      <- exit match
                 case Exit.Success(v) => ZIO.succeed(v)
                 case Exit.Failure(c) => ZIO.failCause(c)
    yield (value, navigation)

  def applyInterceptHalt[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    interceptModel: Model,
    reply: Option[Json],
    navigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      diff <- updateModelAndSubscriptions(modelVar, el, interceptModel, state)
      payload = interceptReplyPayload(reply, diff)
      _ <- publishPayload(payload, meta, state)
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(
                   modelVar,
                   el,
                   interceptModel,
                   command,
                   meta,
                   state
                 )
             case None => ZIO.unit
    yield ()

  def applyBoundEvent[Msg, Model](
    modelVar: Var[Model],
    el: HtmlElement,
    interceptModel: Model,
    event: Payload.Event,
    carriedNavigation: Option[LiveNavigationCommand],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    el.findBinding(event.event) match
      case Some(binding) =>
        for
          (updatedModel, navigation) <-
            captureNavigation(state, carriedNavigation)(
              LiveIO
                .toZIO(state.lv.update(interceptModel)(binding(event.params)))
                .provide(ZLayer.succeed(state.ctx))
            )
          _ <- navigation match
                 case Some(command) =>
                   publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
                     state.patchRedirectCountRef.set(0) *>
                     SocketInbound.handleNavigationCommand(
                       modelVar,
                       el,
                       updatedModel,
                       command,
                       meta,
                       state
                     )
                 case None =>
                   for
                     diff <- updateModelAndSubscriptions(modelVar, el, updatedModel, state)
                     _    <- publishPayload(Payload.okReply(LiveResponse.Diff(diff)), meta, state)
                   yield ()
        yield ()
      case None =>
        carriedNavigation match
          case Some(command) =>
            publishPayload(Payload.okReply(LiveResponse.Empty), meta, state) *>
              state.patchRedirectCountRef.set(0) *>
              SocketInbound.handleNavigationCommand(
                modelVar,
                el,
                interceptModel,
                command,
                meta,
                state
              )
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

  private def interceptReplyPayload(
    reply: Option[Json],
    diff: Diff
  ): Payload =
    reply match
      case Some(replyValue) =>
        Payload.okReply(LiveResponse.InterceptReply(replyValue, Option.when(!diff.isEmpty)(diff)))
      case None if !diff.isEmpty =>
        Payload.okReply(LiveResponse.Diff(diff))
      case None =>
        Payload.okReply(LiveResponse.Empty)
end SocketModelRuntime
