package scalive

import zio.*
import zio.http.URL

final private[scalive] case class LiveHookRuntimeState(
  rawEventHooks: Vector[StoredRawEventHook],
  eventHooks: Vector[StoredEventHook],
  paramsHooks: Vector[StoredParamsHook],
  infoHooks: Vector[StoredInfoHook],
  asyncHooks: Vector[StoredAsyncHook],
  afterRenderHooks: Vector[StoredAfterRenderHook])

private[scalive] object LiveHookRuntimeState:
  val empty: LiveHookRuntimeState =
    LiveHookRuntimeState(
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  def root[Msg, Model](hooks: LiveHooks[Msg, Model]): LiveHookRuntimeState =
    val withRaw = hooks.rawEventHooks.foldLeft(empty)((state, hook) =>
      appendRawEvent(state, rootRawEvent(hook))
    )
    val withEvent =
      hooks.eventHooks.foldLeft(withRaw)((state, hook) => appendEvent(state, rootEvent(hook)))
    val withParams =
      hooks.paramsHooks.foldLeft(withEvent)((state, hook) => appendParams(state, rootParams(hook)))
    val withInfo =
      hooks.infoHooks.foldLeft(withParams)((state, hook) => appendInfo(state, rootInfo(hook)))
    val withAsync =
      hooks.asyncHooks.foldLeft(withInfo)((state, hook) => appendAsync(state, rootAsync(hook)))
    hooks.afterRenderHooks.foldLeft(withAsync)((state, hook) =>
      appendAfterRender(state, rootAfterRender(hook))
    )

  def component[Props, Msg, Model](
    hooks: ComponentLiveHooks[Props, Msg, Model]
  ): LiveHookRuntimeState =
    val withRaw = hooks.rawEventHooks.foldLeft(empty)((state, hook) =>
      appendRawEvent(state, componentRawEvent(hook))
    )
    val withEvent =
      hooks.eventHooks.foldLeft(withRaw)((state, hook) => appendEvent(state, componentEvent(hook)))
    val withAsync = hooks.asyncHooks.foldLeft(withEvent)((state, hook) =>
      appendAsync(state, componentAsync(hook))
    )
    hooks.afterRenderHooks.foldLeft(withAsync)((state, hook) =>
      appendAfterRender(state, componentAfterRender(hook))
    )

  private def appendRawEvent(
    state: LiveHookRuntimeState,
    hook: StoredRawEventHook
  ): LiveHookRuntimeState =
    state.copy(rawEventHooks = state.rawEventHooks :+ hook)

  private def appendEvent(state: LiveHookRuntimeState, hook: StoredEventHook)
    : LiveHookRuntimeState =
    state.copy(eventHooks = state.eventHooks :+ hook)

  private def appendParams(
    state: LiveHookRuntimeState,
    hook: StoredParamsHook
  ): LiveHookRuntimeState =
    state.copy(paramsHooks = state.paramsHooks :+ hook)

  private def appendInfo(state: LiveHookRuntimeState, hook: StoredInfoHook): LiveHookRuntimeState =
    state.copy(infoHooks = state.infoHooks :+ hook)

  private def appendAsync(state: LiveHookRuntimeState, hook: StoredAsyncHook)
    : LiveHookRuntimeState =
    state.copy(asyncHooks = state.asyncHooks :+ hook)

  private def appendAfterRender(
    state: LiveHookRuntimeState,
    hook: StoredAfterRenderHook
  ): LiveHookRuntimeState =
    state.copy(afterRenderHooks = state.afterRenderHooks :+ hook)

  private def rootRawEvent[Msg, Model](
    hook: LiveHooks.RawEvent[Msg, Model]
  ): StoredRawEventHook =
    StoredRawEventHook(
      hook.id,
      (_, model, event, ctx) =>
        hook
          .hook(
            model.asInstanceOf[Model],
            event,
            ctx.messageContext[Msg, Model]
          )
          .map(_.asInstanceOf[LiveEventHookResult[Any]])
    )

  private def componentRawEvent[Props, Msg, Model](
    hook: ComponentLiveHooks.RawEvent[Props, Msg, Model]
  ): StoredRawEventHook =
    StoredRawEventHook(
      hook.id,
      (props, model, event, ctx) =>
        hook
          .hook(
            props.get.asInstanceOf[Props],
            model.asInstanceOf[Model],
            event,
            ctx.componentMessageContext[Props, Msg, Model]
          )
          .map(_.asInstanceOf[LiveEventHookResult[Any]])
    )

  private def rootEvent[Msg, Model](hook: LiveHooks.Event[Msg, Model]): StoredEventHook =
    StoredEventHook(
      hook.id,
      (_, model, message, event, ctx) =>
        hook
          .hook(
            model.asInstanceOf[Model],
            message.asInstanceOf[Msg],
            event,
            ctx.messageContext[Msg, Model]
          )
          .map(_.asInstanceOf[LiveEventHookResult[Any]])
    )

  private def componentEvent[Props, Msg, Model](
    hook: ComponentLiveHooks.Event[Props, Msg, Model]
  ): StoredEventHook =
    StoredEventHook(
      hook.id,
      (props, model, message, event, ctx) =>
        hook
          .hook(
            props.get.asInstanceOf[Props],
            model.asInstanceOf[Model],
            message.asInstanceOf[Msg],
            event,
            ctx.componentMessageContext[Props, Msg, Model]
          )
          .map(_.asInstanceOf[LiveEventHookResult[Any]])
    )

  private def rootParams[Msg, Model](hook: LiveHooks.Params[Msg, Model]): StoredParamsHook =
    StoredParamsHook(
      hook.id,
      (model, url, ctx) =>
        hook
          .hook(model.asInstanceOf[Model], url, ctx.paramsContext[Msg, Model])
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )

  private def rootInfo[Msg, Model](hook: LiveHooks.Info[Msg, Model]): StoredInfoHook =
    StoredInfoHook(
      hook.id,
      (model, message, ctx) =>
        hook
          .hook(
            model.asInstanceOf[Model],
            message.asInstanceOf[Msg],
            ctx.messageContext[Msg, Model]
          )
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )

  private def rootAsync[Msg, Model](hook: LiveHooks.Async[Msg, Model]): StoredAsyncHook =
    StoredAsyncHook(
      hook.id,
      (_, model, event, ctx) =>
        hook
          .hook(
            model.asInstanceOf[Model],
            event.asInstanceOf[LiveAsyncEvent[Msg]],
            ctx.messageContext[Msg, Model]
          )
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )

  private def componentAsync[Props, Msg, Model](
    hook: ComponentLiveHooks.Async[Props, Msg, Model]
  ): StoredAsyncHook =
    StoredAsyncHook(
      hook.id,
      (props, model, event, ctx) =>
        hook
          .hook(
            props.get.asInstanceOf[Props],
            model.asInstanceOf[Model],
            event.asInstanceOf[LiveAsyncEvent[Msg]],
            ctx.componentMessageContext[Props, Msg, Model]
          )
          .map(_.asInstanceOf[LiveHookResult[Any]])
    )

  private def rootAfterRender[Msg, Model](
    hook: LiveHooks.AfterRender[Msg, Model]
  ): StoredAfterRenderHook =
    StoredAfterRenderHook(
      hook.id,
      (_, model, ctx) =>
        hook
          .hook(model.asInstanceOf[Model], ctx.afterRenderContext[Msg, Model])
          .map(_.asInstanceOf[Any])
    )

  private def componentAfterRender[Props, Msg, Model](
    hook: ComponentLiveHooks.AfterRender[Props, Msg, Model]
  ): StoredAfterRenderHook =
    StoredAfterRenderHook(
      hook.id,
      (props, model, ctx) =>
        hook
          .hook(
            props.get.asInstanceOf[Props],
            model.asInstanceOf[Model],
            ctx.componentAfterRenderContext[Props, Msg, Model]
          )
          .map(_.asInstanceOf[Any])
    )
end LiveHookRuntimeState

final private[scalive] case class StoredRawEventHook(
  id: String,
  run: (Option[Any], Any, LiveEvent, LiveContext) => Task[LiveEventHookResult[Any]])

final private[scalive] case class StoredEventHook(
  id: String,
  run: (Option[Any], Any, Any, LiveEvent, LiveContext) => Task[LiveEventHookResult[Any]])

final private[scalive] case class StoredParamsHook(
  id: String,
  run: (Any, URL, LiveContext) => Task[LiveHookResult[Any]])

final private[scalive] case class StoredInfoHook(
  id: String,
  run: (Any, Any, LiveContext) => Task[LiveHookResult[Any]])

final private[scalive] case class StoredAsyncHook(
  id: String,
  run: (Option[Any], Any, LiveAsyncEvent[Any], LiveContext) => Task[LiveHookResult[Any]])

final private[scalive] case class StoredAfterRenderHook(
  id: String,
  run: (Option[Any], Any, LiveContext) => Task[Any])
