package scalive

import zio.*
import zio.http.URL

private[scalive] class SocketLiveHookRuntime(ref: Ref[LiveHookRuntimeState])
    extends LiveHookRuntime:

  def attachRawEvent[Msg, Model](
    id: String
  )(
    hook: (Model, LiveEvent, MessageContext[Msg, Model]) => LiveIO[LiveEventHookResult[Model]]
  ): Task[Unit] =
    attach(
      id,
      "rawEvent",
      _.rawEventHooks.exists(_.id == id),
      state =>
        state.copy(rawEventHooks =
          state.rawEventHooks :+ StoredRawEventHook(
            id,
            (_, model, event, ctx) =>
              hook(model.asInstanceOf[Model], event, ctx.messageContext[Msg, Model])
                .map(_.asInstanceOf[LiveEventHookResult[Any]])
          )
        )
    )

  def attachComponentRawEvent[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit] =
    attach(
      id,
      "rawEvent",
      _.rawEventHooks.exists(_.id == id),
      state =>
        state.copy(rawEventHooks =
          state.rawEventHooks :+ StoredRawEventHook(
            id,
            (props, model, event, ctx) =>
              hook(
                props.get.asInstanceOf[Props],
                model.asInstanceOf[Model],
                event,
                ctx.componentMessageContext[Props, Msg, Model]
              )
                .map(_.asInstanceOf[LiveEventHookResult[Any]])
          )
        )
    )

  def detachRawEvent(id: String): Task[Unit] =
    ref.update(state => state.copy(rawEventHooks = state.rawEventHooks.filterNot(_.id == id)))

  def attachEvent[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, LiveEvent, MessageContext[Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit] =
    attach(
      id,
      "event",
      _.eventHooks.exists(_.id == id),
      state =>
        state.copy(eventHooks =
          state.eventHooks :+ StoredEventHook(
            id,
            (_, model, message, event, ctx) =>
              hook(
                model.asInstanceOf[Model],
                message.asInstanceOf[Msg],
                event,
                ctx.messageContext[Msg, Model]
              )
                .map(_.asInstanceOf[LiveEventHookResult[Any]])
          )
        )
    )

  def attachComponentEvent[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, Msg, LiveEvent, ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveEventHookResult[Model]
    ]
  ): Task[Unit] =
    attach(
      id,
      "event",
      _.eventHooks.exists(_.id == id),
      state =>
        state.copy(eventHooks =
          state.eventHooks :+ StoredEventHook(
            id,
            (props, model, message, event, ctx) =>
              hook(
                props.get.asInstanceOf[Props],
                model.asInstanceOf[Model],
                message.asInstanceOf[Msg],
                event,
                ctx.componentMessageContext[Props, Msg, Model]
              )
                .map(_.asInstanceOf[LiveEventHookResult[Any]])
          )
        )
    )

  def detachEvent(id: String): Task[Unit] =
    ref.update(state => state.copy(eventHooks = state.eventHooks.filterNot(_.id == id)))

  def attachParams[Msg, Model](
    id: String
  )(
    hook: (Model, URL, ParamsContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): Task[Unit] =
    attach(
      id,
      "params",
      _.paramsHooks.exists(_.id == id),
      state =>
        state.copy(paramsHooks =
          state.paramsHooks :+ StoredParamsHook(
            id,
            (model, url, ctx) =>
              hook(model.asInstanceOf[Model], url, ctx.paramsContext[Msg, Model])
                .map(_.asInstanceOf[LiveHookResult[Any]])
          )
        )
    )

  def detachParams(id: String): Task[Unit] =
    ref.update(state => state.copy(paramsHooks = state.paramsHooks.filterNot(_.id == id)))

  def attachInfo[Msg, Model](
    id: String
  )(
    hook: (Model, Msg, MessageContext[Msg, Model]) => LiveIO[LiveHookResult[Model]]
  ): Task[Unit] =
    attach(
      id,
      "info",
      _.infoHooks.exists(_.id == id),
      state =>
        state.copy(infoHooks =
          state.infoHooks :+ StoredInfoHook(
            id,
            (model, message, ctx) =>
              hook(
                model.asInstanceOf[Model],
                message.asInstanceOf[Msg],
                ctx.messageContext[Msg, Model]
              )
                .map(_.asInstanceOf[LiveHookResult[Any]])
          )
        )
    )

  def detachInfo(id: String): Task[Unit] =
    ref.update(state => state.copy(infoHooks = state.infoHooks.filterNot(_.id == id)))

  def attachAsync[Msg, Model](
    id: String
  )(
    hook: (Model, LiveAsyncEvent[Msg], MessageContext[Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): Task[Unit] =
    attach(
      id,
      "async",
      _.asyncHooks.exists(_.id == id),
      state =>
        state.copy(asyncHooks =
          state.asyncHooks :+ StoredAsyncHook(
            id,
            (_, model, event, ctx) =>
              hook(
                model.asInstanceOf[Model],
                event.asInstanceOf[LiveAsyncEvent[Msg]],
                ctx.messageContext[Msg, Model]
              )
                .map(_.asInstanceOf[LiveHookResult[Any]])
          )
        )
    )

  def attachComponentAsync[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, LiveAsyncEvent[Msg], ComponentMessageContext[Props, Msg, Model]) => LiveIO[
      LiveHookResult[Model]
    ]
  ): Task[Unit] =
    attach(
      id,
      "async",
      _.asyncHooks.exists(_.id == id),
      state =>
        state.copy(asyncHooks =
          state.asyncHooks :+ StoredAsyncHook(
            id,
            (props, model, event, ctx) =>
              hook(
                props.get.asInstanceOf[Props],
                model.asInstanceOf[Model],
                event.asInstanceOf[LiveAsyncEvent[Msg]],
                ctx.componentMessageContext[Props, Msg, Model]
              )
                .map(_.asInstanceOf[LiveHookResult[Any]])
          )
        )
    )

  def detachAsync(id: String): Task[Unit] =
    ref.update(state => state.copy(asyncHooks = state.asyncHooks.filterNot(_.id == id)))

  def attachAfterRender[Msg, Model](
    id: String
  )(
    hook: (Model, AfterRenderContext[Msg, Model]) => LiveIO[Model]
  ): Task[Unit] =
    attach(
      id,
      "afterRender",
      _.afterRenderHooks.exists(_.id == id),
      state =>
        state.copy(afterRenderHooks =
          state.afterRenderHooks :+ StoredAfterRenderHook(
            id,
            (_, model, ctx) =>
              hook(model.asInstanceOf[Model], ctx.afterRenderContext[Msg, Model])
                .map(_.asInstanceOf[Any])
          )
        )
    )

  def attachComponentAfterRender[Props, Msg, Model](
    id: String
  )(
    hook: (Props, Model, ComponentAfterRenderContext[Props, Msg, Model]) => LiveIO[Model]
  ): Task[Unit] =
    attach(
      id,
      "afterRender",
      _.afterRenderHooks.exists(_.id == id),
      state =>
        state.copy(afterRenderHooks =
          state.afterRenderHooks :+ StoredAfterRenderHook(
            id,
            (props, model, ctx) =>
              hook(
                props.get.asInstanceOf[Props],
                model.asInstanceOf[Model],
                ctx.componentAfterRenderContext[Props, Msg, Model]
              )
                .map(_.asInstanceOf[Any])
          )
        )
    )

  def detachAfterRender(id: String): Task[Unit] =
    ref.update(state => state.copy(afterRenderHooks = state.afterRenderHooks.filterNot(_.id == id)))

  private[scalive] def runRawEvent[Msg, Model](
    model: Model,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    ref.get.flatMap(state => reduceEventHooks(state.rawEventHooks, None, model, event, ctx))

  private[scalive] def runComponentRawEvent[Props, Msg, Model](
    props: Props,
    model: Model,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    ref.get.flatMap(state => reduceEventHooks(state.rawEventHooks, Some(props), model, event, ctx))

  private[scalive] def runEvent[Msg, Model](
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    ref.get.flatMap(state =>
      reduceTypedEventHooks(state.eventHooks, None, model, message, event, ctx)
    )

  private[scalive] def runComponentEvent[Props, Msg, Model](
    props: Props,
    model: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    ref.get.flatMap(state =>
      reduceTypedEventHooks(state.eventHooks, Some(props), model, message, event, ctx)
    )

  private[scalive] def runParams[Msg, Model](
    model: Model,
    url: URL,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceParamsHooks(state.paramsHooks, model, url, ctx))

  private[scalive] def runInfo[Msg, Model](
    model: Model,
    message: Msg,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceInfoHooks(state.infoHooks, model, message, ctx))

  private[scalive] def runAsync[Msg, Model](
    model: Model,
    event: LiveAsyncEvent[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceAsyncHooks(state.asyncHooks, None, model, event, ctx))

  private[scalive] def runComponentAsync[Props, Msg, Model](
    props: Props,
    model: Model,
    event: LiveAsyncEvent[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    ref.get.flatMap(state => reduceAsyncHooks(state.asyncHooks, Some(props), model, event, ctx))

  private[scalive] def runAfterRender[Msg, Model](model: Model, ctx: LiveContext): Task[Model] =
    ref.get.flatMap(state => reduceAfterRenderHooks(state.afterRenderHooks, None, model, ctx))

  private[scalive] def runComponentAfterRender[Props, Msg, Model](
    props: Props,
    model: Model,
    ctx: LiveContext
  ): Task[Model] =
    ref.get.flatMap(state =>
      reduceAfterRenderHooks(state.afterRenderHooks, Some(props), model, ctx)
    )

  private def attach(
    id: String,
    stage: String,
    exists: LiveHookRuntimeState => Boolean,
    update: LiveHookRuntimeState => LiveHookRuntimeState
  ): Task[Unit] =
    ref
      .modify { state =>
        if exists(state) then Left(duplicateError(id, stage)) -> state
        else Right(())                                        -> update(state)
      }.flatMap(ZIO.fromEither(_))

  private def reduceEventHooks[Model](
    hooks: Vector[StoredRawEventHook],
    props: Option[Any],
    initialModel: Model,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(
          LiveEventHookResult.Continue(initialModel).asInstanceOf[LiveEventHookResult[Any]]
        ): Task[LiveEventHookResult[Any]]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveEventHookResult.Continue(model)   => hook.run(props, model, event, ctx)
          case halt @ LiveEventHookResult.Halt(_, _) => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveEventHookResult[Model]])

  private def reduceTypedEventHooks[Msg, Model](
    hooks: Vector[StoredEventHook],
    props: Option[Any],
    initialModel: Model,
    message: Msg,
    event: LiveEvent,
    ctx: LiveContext
  ): Task[LiveEventHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(
          LiveEventHookResult.Continue(initialModel).asInstanceOf[LiveEventHookResult[Any]]
        ): Task[LiveEventHookResult[Any]]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveEventHookResult.Continue(model)   => hook.run(props, model, message, event, ctx)
          case halt @ LiveEventHookResult.Halt(_, _) => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveEventHookResult[Model]])

  private def reduceParamsHooks[Model](
    hooks: Vector[StoredParamsHook],
    initialModel: Model,
    url: URL,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(LiveHookResult.Continue(initialModel).asInstanceOf[LiveHookResult[Any]]): Task[
          LiveHookResult[Any]
        ]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveHookResult.Continue(model) => hook.run(model, url, ctx)
          case halt @ LiveHookResult.Halt(_)  => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveHookResult[Model]])

  private def reduceInfoHooks[Msg, Model](
    hooks: Vector[StoredInfoHook],
    initialModel: Model,
    message: Msg,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(LiveHookResult.Continue(initialModel).asInstanceOf[LiveHookResult[Any]]): Task[
          LiveHookResult[Any]
        ]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveHookResult.Continue(model) => hook.run(model, message, ctx)
          case halt @ LiveHookResult.Halt(_)  => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveHookResult[Model]])

  private def reduceAsyncHooks[Msg, Model](
    hooks: Vector[StoredAsyncHook],
    props: Option[Any],
    initialModel: Model,
    event: LiveAsyncEvent[Msg],
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hooks
      .foldLeft(
        ZIO.succeed(LiveHookResult.Continue(initialModel).asInstanceOf[LiveHookResult[Any]]): Task[
          LiveHookResult[Any]
        ]
      ) { case (current, hook) =>
        current.flatMap {
          case LiveHookResult.Continue(model) =>
            hook.run(props, model, event.asInstanceOf[LiveAsyncEvent[Any]], ctx)
          case halt @ LiveHookResult.Halt(_) => ZIO.succeed(halt)
        }
      }.map(_.asInstanceOf[LiveHookResult[Model]])

  private def reduceAfterRenderHooks[Model](
    hooks: Vector[StoredAfterRenderHook],
    props: Option[Any],
    initialModel: Model,
    ctx: LiveContext
  ): Task[Model] =
    hooks
      .foldLeft(ZIO.succeed(initialModel.asInstanceOf[Any]): Task[Any]) { case (current, hook) =>
        current.flatMap(model => hook.run(props, model, ctx))
      }.map(_.asInstanceOf[Model])

  private def duplicateError(id: String, stage: String): IllegalArgumentException =
    new IllegalArgumentException(s"$stage hook '$id' is already attached")
end SocketLiveHookRuntime
