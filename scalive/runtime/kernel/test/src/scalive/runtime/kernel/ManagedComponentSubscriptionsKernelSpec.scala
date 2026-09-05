package scalive.runtime.kernel

import zio.*
import zio.http.URL
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

object ManagedComponentSubscriptionsKernelSpec extends ZIOSpecDefault:
  private val config = SessionConfig.make(8, 8).toOption.get

  private final case class Subscription(
    key: SubscriptionKey,
    delivery: SubscriptionDelivery,
    stream: ZStream[Any, Nothing, ComponentMessage]
  )

  private enum RootMessage:
    case Hide, Show, Navigate

  private enum ComponentMessage:
    case Value(value: Int)
    case Navigate
    case NavigateAndShow
    case CancelAndHide(key: SubscriptionKey)
    case ReplaceAndHide(subscription: Subscription)
    case StartThenFail(subscription: Subscription)
    case Duplicate(subscription: Subscription)

  private final class Reservation extends OutboundReservation[SessionOutput]:
    def publish(batch: OutboundBatch[SessionOutput]) = ZIO.unit
    def release                                         = ZIO.unit

  private final class Outbound extends OutboundReservations[SessionOutput]:
    def reserve  = ZIO.succeed(Reservation())
    def take     = ZIO.fail(OutboundReservationError.Shutdown)
    def shutdown = ZIO.unit

  private final class Environment(
    lifecycle: LifecycleId,
    initial: Map[AnyRef, Subscription],
    mounts: Ref[Int],
    updates: Ref[Int],
    messages: Ref[Int],
    failAfterRender: Ref[Boolean]
  ) extends ComponentEnvironment[RootMessage, Boolean]:
    private def start(
      id: ComponentInstanceId,
      subscription: Subscription,
      replace: Boolean
    ): ResourceOperation =
      ResourceOperation.StartSubscription(
        OwnerId.Component(lifecycle, id),
        subscription.key,
        subscription.delivery,
        subscription.stream,
        replace
      )

    def mount[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      draft: TurnDraft[RootMessage, Boolean]
    ) =
      mounts.update(_ + 1).as(
        ComponentCallbackResult(
          Vector.empty[Int].asInstanceOf[A],
          draft,
          ComponentEnvironmentState(new Object())
        )
      )

    def update[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      state: ComponentEnvironmentState,
      draft: TurnDraft[RootMessage, Boolean]
    ) =
      val operation = initial.get(component.asInstanceOf[AnyRef]).map(start(id, _, replace = false))
      updates.update(_ + 1).as(
        ComponentCallbackResult(
          model,
          draft.copy(resourceOperations = draft.resourceOperations ++ operation),
          state
        )
      )

    def message[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      value: M,
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[RootMessage, Boolean]
    ) =
      messages.update(_ + 1) *> (value.asInstanceOf[ComponentMessage] match
        case ComponentMessage.Value(value) =>
          val next = model.asInstanceOf[Vector[Int]] :+ value
          ZIO.succeed(ComponentCallbackResult(next.asInstanceOf[A], draft, state))
        case ComponentMessage.Navigate =>
          ZIO.succeed(
            ComponentCallbackResult(
              model,
              draft.copy(
                navigation = Some(
                  NavigationRequest(
                    URL.decode("/managed-component-subscriptions").toOption.get,
                    NavigationKind.PushPatch
                  )
                )
              ),
              state
            )
          )
        case ComponentMessage.NavigateAndShow =>
          ZIO.succeed(
            ComponentCallbackResult(
              model,
              draft.copy(
                model = true,
                navigation = Some(
                  NavigationRequest(
                    URL.decode("/managed-component-subscriptions").toOption.get,
                    NavigationKind.PushPatch
                  )
                )
              ),
              state
            )
          )
        case ComponentMessage.CancelAndHide(key) =>
          ZIO.succeed(
            ComponentCallbackResult(
              model,
              draft.copy(
                model = false,
                resourceOperations = draft.resourceOperations :+
                  ResourceOperation.CancelSubscription(OwnerId.Component(lifecycle, id), key)
              ),
              state
            )
          )
        case ComponentMessage.ReplaceAndHide(subscription) =>
          ZIO.succeed(
            ComponentCallbackResult(
              model,
              draft.copy(
                model = false,
                resourceOperations = draft.resourceOperations :+ start(id, subscription, replace = true)
              ),
              state
            )
          )
        case ComponentMessage.StartThenFail(subscription) =>
          failAfterRender.set(true).as(
            ComponentCallbackResult(
              model,
              draft.copy(resourceOperations = draft.resourceOperations :+ start(id, subscription, replace = false)),
              state
            )
          )
        case ComponentMessage.Duplicate(subscription) =>
          ZIO.succeed(
            ComponentCallbackResult(
              model,
              draft.copy(resourceOperations = draft.resourceOperations :+ start(id, subscription, replace = false)),
              state
            )
          ))

    def async[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      event: LiveAsyncEvent[M],
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[RootMessage, Boolean]
    ) = ZIO.succeed(ComponentCallbackResult(model, draft, state))

    def browserEvent[P, M, A, O](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      command: SessionCommand.ComponentClientEvent,
      resolved: Task[M],
      emit: O => Task[Unit],
      state: ComponentEnvironmentState,
      draft: TurnDraft[RootMessage, Boolean]
    ) = resolved.flatMap(value => message(id, component, props, model, value, emit, state, draft))

    def afterRender[P, M, A](
      id: ComponentInstanceId,
      component: LiveComponent[P, M, A],
      props: P,
      model: A,
      state: ComponentEnvironmentState,
      draft: TurnDraft[RootMessage, Boolean]
    ) = ZIO.succeed(ComponentAfterRenderResult(draft, state))

    def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit
    def close(id: ComponentInstanceId, state: ComponentEnvironmentState)   = ZIO.unit

  private def definition(
    child: Option[LiveComponentInstance[Int, ComponentMessage, Vector[Int]]] = None
  ): LiveComponent[Int, ComponentMessage, Vector[Int]] =
    new LiveComponent[Int, ComponentMessage, Vector[Int]]:
      def mount(props: Int, ctx: MountContext) = ZIO.succeed(Vector.empty)
      def handleMessage(props: Int, model: Vector[Int], ctx: MessageContext) =
        case ComponentMessage.Value(value) => ZIO.succeed(model :+ value)
        case _                             => ZIO.succeed(model)
      def view(
        props: Signal[Int],
        model: Signal[Vector[Int]],
        self: ComponentRef[ComponentMessage]
      ) = child.fold(div(model.map(_.mkString(","))))(value => div(value.render(0)))

  private def program(
    instance: LiveComponentInstance[Int, ComponentMessage, Vector[Int]]
  ) =
    ZIO.fromEither(
      RenderProgram.compile[Boolean, RootMessage](model => div(model.when(div(instance.render(0)))))
    )

  private def logic(
    guards: Ref[Int],
    afterRender: TurnDraft[RootMessage, Boolean] => Task[TurnDraft[RootMessage, Boolean]] =
      draft => ZIO.succeed(draft)
  ): SessionLogic[RootMessage, Boolean] =
    SessionLogic(
      bootstrap = ZIO.succeed(TurnDraft(true)),
      handle = (_, message) => message match
        case RootMessage.Hide => ZIO.succeed(TurnDraft(false))
        case RootMessage.Show => ZIO.succeed(TurnDraft(true))
        case RootMessage.Navigate =>
          ZIO.succeed(
            TurnDraft(
              false,
              navigation = Some(
                NavigationRequest(
                  URL.decode("/managed-component-subscriptions").toOption.get,
                  NavigationKind.PushPatch
                )
              )
            )
          ),
      guardConnectedTurn = guards.update(_ + 1).as(Right(())),
      afterRender = afterRender
    )

  private def environment(
    lifecycle: LifecycleId,
    initial: Map[AnyRef, Subscription]
  ) =
    for
      mounts <- Ref.make(0)
      updates <- Ref.make(0)
      messages <- Ref.make(0)
      failAfterRender <- Ref.make(false)
    yield (
      Environment(lifecycle, initial, mounts, updates, messages, failAfterRender),
      mounts,
      updates,
      messages,
      failAfterRender
    )

  private def retrySaturated[A](
    effect: => ZIO[Any, SessionRejection, A]
  ): ZIO[Any, SessionRejection, A] =
    effect.catchSome {
      case SessionRejection.MailboxSaturated(_) => ZIO.yieldNow *> retrySaturated(effect)
    }

  override def spec = suite("ManagedComponentSubscriptionsKernelSpec")(
    test("dormant runs are stopped and revival rejects the old token before component dispatch") {
      ZIO.scoped {
        val lifecycle = LifecycleId(201L)
        val key       = SubscriptionKey("revive")
        for
          starts      <- Ref.make(0)
          interrupted <- Ref.make(0)
          acquired    <- Promise.make[Nothing, Unit]
          stream = ZStream.unwrap(
                     starts.updateAndGet(_ + 1).map(_ =>
                       (ZStream.fromZIO(acquired.succeed(())) *> ZStream.never)
                         .ensuring(interrupted.update(_ + 1))
                     )
                   )
          componentDefinition = definition()
          instance            = component(componentDefinition, "revive")
          (env, mounts, updates, messages, _) <- environment(
                                                   lifecycle,
                                                   Map(componentDefinition.asInstanceOf[AnyRef] ->
                                                     Subscription(key, SubscriptionDelivery.Lossless, stream))
                                                 )
          guards <- Ref.make(0)
          render <- program(instance)
          kernel <- SessionKernel.start(
                      config,
                      logic(guards),
                      render,
                      Outbound(),
                      env,
                      providedLifecycle = Some(lifecycle)
                    )
          _      <- acquired.await
          before <- kernel.inspect
          mounted = before.components.values.head
          old     = before.managedResources.values.head.asInstanceOf[ManagedResource.RunningSubscription]
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
          _       <- interrupted.get.repeatUntil(_ == 1)
          dormant <- kernel.inspect
          suspended = dormant.managedResources.values.head
            .asInstanceOf[ManagedResource.SuspendedSubscription]
          oldState <- old.prepared.state
          dormantStale <- kernel.submit(
                            SessionCommand.ManagedSubscription(
                              kernel.epoch,
                              old.token,
                              ComponentMessage.Value(8)
                            )
                          ).either
          dormantGuards   <- guards.get
          dormantMessages <- messages.get
          _        <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Show))
          _        <- starts.get.repeatUntil(_ == 2)
          revived  <- kernel.inspect
          fresh = revived.managedResources.values.head
            .asInstanceOf[ManagedResource.RunningSubscription]
          _ <- suspended.ended.set(true)
          staleValue <- kernel.submit(
                          SessionCommand.ManagedSubscription(
                            kernel.epoch,
                            old.token,
                            ComponentMessage.Value(9)
                          )
                        ).either
          staleEnd <- kernel.submit(
                        SessionCommand.ManagedSubscriptionEnded(kernel.epoch, old.token)
                      ).either
          finalState  <- kernel.inspect
          mountCount  <- mounts.get
          updateCount <- updates.get
          messageCount <- messages.get
          guardCount   <- guards.get
        yield assertTrue(
          dormant.components.values.isEmpty,
          suspended.token == old.token,
          oldState == PreparedResource.State.Closed,
          dormantStale == Left(SessionRejection.StaleResource(old.token)),
          dormantGuards == 1,
          dormantMessages == 0,
          fresh.token != old.token,
          revived.components.values.head.id == mounted.id,
          revived.components.values.head.model == Vector.empty[Int],
          mountCount == 1,
          updateCount == 1,
          messageCount == 0,
          guardCount == 2,
          staleValue == Left(SessionRejection.StaleResource(old.token)),
          staleEnd == Left(SessionRejection.StaleResource(old.token)),
          finalState.managedResources.current(fresh.token).nonEmpty
        )
      }
    },
    test("a terminal run cannot restart when revival was queued before its End marker") {
      enum Terminal:
        case Complete, Defect

      ZIO.foreach(SubscriptionDelivery.values.toVector) { delivery =>
        ZIO.foreach(Terminal.values.toVector) { terminal =>
          ZIO.scoped {
            val lifecycle = LifecycleId(210L + delivery.ordinal * 10L + terminal.ordinal)
            for
              starts  <- Ref.make(0)
              source  <- Promise.make[Nothing, Unit]
              entered <- Promise.make[Nothing, Unit]
              release <- Promise.make[Nothing, Unit]
              base = ZStream.fromZIO(source.await).drain
              stream = ZStream.unwrap(
                         starts.updateAndGet(_ + 1).map(_ => terminal match
                           case Terminal.Complete => base
                           case Terminal.Defect   => base ++ ZStream.dieMessage("terminal defect")
                         )
                       )
              componentDefinition = definition()
              instance            = component(componentDefinition, s"terminal-$delivery-$terminal")
              (env, _, _, _, _) <- environment(
                                     lifecycle,
                                     Map(componentDefinition.asInstanceOf[AnyRef] ->
                                       Subscription(SubscriptionKey("terminal"), delivery, stream))
                                   )
              guards <- Ref.make(0)
              render <- program(instance)
              gateAfterRender = (draft: TurnDraft[RootMessage, Boolean]) =>
                                  if draft.model then ZIO.succeed(draft)
                                  else entered.succeed(()).unit *> release.await.as(draft)
              kernel <- SessionKernel.start(
                          config,
                          logic(guards, gateAfterRender),
                          render,
                          Outbound(),
                          env,
                          providedLifecycle = Some(lifecycle)
                        )
              initial <- kernel.inspect
              running = initial.managedResources.values.head
                .asInstanceOf[ManagedResource.RunningSubscription]
              componentId = initial.components.values.head.id
              _      <- starts.get.repeatUntil(_ == 1)
              hiding <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide)).fork
              _      <- entered.await
              showId <- ZIO.fromEither(CommandId.fresh())
              show <- kernel.enqueue(
                        showId,
                        SessionCommand.Message(kernel.epoch, RootMessage.Show)
                      )
              _ <- source.succeed(())
              _ <- running.ended.get.repeatUntil(identity)
              _ <- release.succeed(())
              _ <- hiding.join *> show
              committed <- kernel.inspect
              count     <- starts.get
            yield assertTrue(
              committed.components.values.map(_.id) == Vector(componentId),
              committed.managedResources.values.isEmpty,
              count == 1
            )
          }
        }
      }.map(results => assertTrue(results.flatten.forall(_.isSuccess)))
    },
    test("component cancel and replacement survive the callback omitting its owner") {
      enum Change:
        case Cancel, Replace

      ZIO.foreach(Change.values.toVector) { change =>
        ZIO.scoped {
          val lifecycle = LifecycleId(230L + change.ordinal)
          val key       = SubscriptionKey("callback-change")
          for
            oldStarts <- Ref.make(0)
            newStarts <- Ref.make(0)
            oldStream  = ZStream.fromZIO(oldStarts.update(_ + 1)) *> ZStream.never
            newStream  = ZStream.fromZIO(newStarts.update(_ + 1)) *> ZStream.never
            replacement = Subscription(key, SubscriptionDelivery.Latest, newStream)
            componentDefinition = definition()
            instance            = component(componentDefinition, s"change-$change")
            (env, mounts, updates, _, _) <- environment(
                                               lifecycle,
                                               Map(componentDefinition.asInstanceOf[AnyRef] ->
                                                 Subscription(key, SubscriptionDelivery.Lossless, oldStream))
                                             )
            guards <- Ref.make(0)
            render <- program(instance)
            kernel <- SessionKernel.start(
                        config,
                        logic(guards),
                        render,
                        Outbound(),
                        env,
                        providedLifecycle = Some(lifecycle)
                      )
            mounted <- kernel.inspect.map(_.components.values.head)
            _       <- oldStarts.get.repeatUntil(_ == 1)
            command = change match
                        case Change.Cancel  => ComponentMessage.CancelAndHide(key)
                        case Change.Replace => ComponentMessage.ReplaceAndHide(replacement)
            _      <- kernel.submitComponent(mounted.id, command)
            hidden <- kernel.inspect
            hiddenUsesReplacement = hidden.managedResources.values.headOption.exists {
                                      case value: ManagedResource.SuspendedSubscription =>
                                        value.definition.delivery == SubscriptionDelivery.Latest &&
                                          (value.definition.stream.asInstanceOf[AnyRef] eq
                                            newStream.asInstanceOf[AnyRef])
                                      case _ => false
                                    }
            _       <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Show))
            _ <- ZIO.when(change == Change.Replace)(
                   newStarts.get.repeatUntil(_ == 1).unit
                 )
            revived <- kernel.inspect
            oldCount <- oldStarts.get
            newCount <- newStarts.get
            mountCount <- mounts.get
            updateCount <- updates.get
          yield assertTrue(
            hidden.components.values.isEmpty,
            if change == Change.Cancel then hidden.managedResources.values.isEmpty
            else hiddenUsesReplacement,
            if change == Change.Cancel then revived.managedResources.values.isEmpty
            else revived.managedResources.values.headOption.exists(
              _.asInstanceOf[ManagedResource.RunningSubscription].definition.delivery ==
                SubscriptionDelivery.Latest
            ),
            oldCount == 1,
            newCount == (if change == Change.Replace then 1 else 0),
            mountCount == 1,
            updateCount == 1
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("destroying a dormant parent removes suspended resources of its nested descendants") {
      ZIO.foreach(Vector(false, true)) { navigating =>
        ZIO.scoped {
          val lifecycle = LifecycleId(if navigating then 241L else 240L)
          for
            childDefinition  <- ZIO.succeed(definition())
            child             = component(childDefinition, "nested-child")
            parentDefinition  = definition(Some(child))
            parent            = component(parentDefinition, "nested-parent")
            childSubscription = Subscription(
                                  SubscriptionKey("child"),
                                  SubscriptionDelivery.Lossless,
                                  ZStream.never
                                )
            parentSubscription = Subscription(
                                   SubscriptionKey("parent"),
                                   SubscriptionDelivery.Latest,
                                   ZStream.never
                                 )
            (env, _, _, _, _) <- environment(
                                   lifecycle,
                                   Map(
                                     childDefinition.asInstanceOf[AnyRef] -> childSubscription,
                                     parentDefinition.asInstanceOf[AnyRef] -> parentSubscription
                                   )
                                 )
            guards <- Ref.make(0)
            render <- program(parent)
            kernel <- SessionKernel.start(
                        config,
                        logic(guards),
                        render,
                        Outbound(),
                        env,
                        providedLifecycle = Some(lifecycle)
                      )
            before <- kernel.inspect
            parentComponent = before.components.values.find(_.key.applicationId == "nested-parent").get
            token           = parentComponent.ref.asInstanceOf[Object]
            _ <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
            hidden <- kernel.inspect
            _ <- ZIO.when(navigating)(
                   kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Navigate)).unit
                 )
            _     <- kernel.destroyComponents(Vector(token))
            after <- kernel.inspect
          yield assertTrue(
            hidden.managedResources.values.size == 2,
            hidden.managedResources.values.forall(
              _.isInstanceOf[ManagedResource.SuspendedSubscription]
            ),
            after.components.allValues.isEmpty,
            after.managedResources.values.isEmpty
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("destroy during staged component navigation is preserved through acknowledgement") {
      ZIO.scoped {
        val lifecycle  = LifecycleId(245L)
        val destination = URL.decode("/managed-component-subscriptions").toOption.get
        for
          starts   <- Ref.make(0)
          releases <- Ref.make(0)
          stream = (ZStream.fromZIO(starts.update(_ + 1)) *> ZStream.never)
                     .ensuring(releases.update(_ + 1))
          navigatorDefinition = definition()
          targetDefinition    = definition()
          navigator           = component(navigatorDefinition, "navigator")
          target              = component(targetDefinition, "navigation-target")
          (env, _, _, _, _) <- environment(
                                 lifecycle,
                                 Map(targetDefinition.asInstanceOf[AnyRef] ->
                                   Subscription(
                                     SubscriptionKey("navigation-target"),
                                     SubscriptionDelivery.Lossless,
                                     stream
                                   ))
                               )
          guards <- Ref.make(0)
          render <- ZIO.fromEither(
                      RenderProgram.compile[Boolean, RootMessage](model =>
                        div(
                          navigator.render(0),
                          model.when(div(target.render(0)))
                        )
                      )
                    )
          kernel <- SessionKernel.start(
                      config,
                      logic(guards),
                      render,
                      Outbound(),
                      env,
                      providedLifecycle = Some(lifecycle)
                    )
          before <- kernel.inspect
          navigatorId = before.components.values.find(_.key.applicationId == "navigator").get.id
          targetComponent = before.components.values.find(
                              _.key.applicationId == "navigation-target"
                            ).get
          targetToken = targetComponent.ref.asInstanceOf[Object]
          _      <- starts.get.repeatUntil(_ == 1)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
          _      <- releases.get.repeatUntil(_ == 1)
          hidden <- kernel.inspect
          _      <- kernel.submitComponent(navigatorId, ComponentMessage.Navigate)
          _      <- kernel.destroyComponents(Vector(targetToken))
          pendingAfterDestroy <- kernel.inspect
          _ <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, destination))
          committed <- kernel.inspect
          startCount <- starts.get
        yield assertTrue(
          hidden.components.values.map(_.key.applicationId) == Vector("navigator"),
          hidden.managedResources.values.size == 1,
          pendingAfterDestroy.components.allValues.map(_.key.applicationId).toSet ==
            Set("navigator", "navigation-target"),
          pendingAfterDestroy.managedResources.values.size == 1,
          pendingAfterDestroy.managedResources.values.head
            .isInstanceOf[ManagedResource.SuspendedSubscription],
          committed.components.allValues.map(_.key.applicationId) == Vector("navigator"),
          committed.managedResources.values.isEmpty,
          startCount == 1
        )
      }
    },
    test("deferred destruction does not remove a target revived by a component navigation") {
      ZIO.scoped {
        val lifecycle   = LifecycleId(246L)
        val destination = URL.decode("/managed-component-subscriptions").toOption.get
        for
          starts   <- Ref.make(0)
          releases <- Ref.make(0)
          stream = (ZStream.fromZIO(starts.update(_ + 1)) *> ZStream.never)
                     .ensuring(releases.update(_ + 1))
          navigatorDefinition = definition()
          targetDefinition    = definition()
          navigator           = component(navigatorDefinition, "reviving-navigator")
          target              = component(targetDefinition, "revived-navigation-target")
          (env, mounts, _, _, _) <- environment(
                                      lifecycle,
                                      Map(targetDefinition.asInstanceOf[AnyRef] ->
                                        Subscription(
                                          SubscriptionKey("revived-navigation-target"),
                                          SubscriptionDelivery.Latest,
                                          stream
                                        ))
                                    )
          guards <- Ref.make(0)
          render <- ZIO.fromEither(
                      RenderProgram.compile[Boolean, RootMessage](model =>
                        div(
                          navigator.render(0),
                          model.when(div(target.render(0)))
                        )
                      )
                    )
          kernel <- SessionKernel.start(
                      config,
                      logic(guards),
                      render,
                      Outbound(),
                      env,
                      providedLifecycle = Some(lifecycle)
                    )
          before <- kernel.inspect
          navigatorId = before.components.values.find(_.key.applicationId == "reviving-navigator").get.id
          targetComponent = before.components.values.find(
                              _.key.applicationId == "revived-navigation-target"
                            ).get
          targetToken = targetComponent.ref.asInstanceOf[Object]
          _ <- starts.get.repeatUntil(_ == 1)
          _ <- kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
          _ <- releases.get.repeatUntil(_ == 1)
          _ <- kernel.submitComponent(navigatorId, ComponentMessage.NavigateAndShow)
          _ <- kernel.destroyComponents(Vector(targetToken))
          pending <- kernel.inspect
          _ <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, destination))
          _ <- starts.get.repeatUntil(_ == 2)
          committed <- kernel.inspect
          mountCount <- mounts.get
          startCount <- starts.get
          running = committed.managedResources.values.headOption.collect {
                      case value: ManagedResource.RunningSubscription => value
                    }
        yield assertTrue(
          pending.components.values.map(_.key.applicationId) == Vector("reviving-navigator"),
          committed.components.values.find(_.key.applicationId == "revived-navigation-target")
            .exists(_.id == targetComponent.id),
          running.exists(_.token.owner == OwnerId.Component(lifecycle, targetComponent.id)),
          startCount == 2,
          mountCount == 2
        )
      }
    },
    test("subscription pressure across repeated suspension and cancellation preserves mailbox permits") {
      ZIO.foreach(SubscriptionDelivery.values.toVector) { delivery =>
        ZIO.scoped {
          val lifecycle   = LifecycleId(247L + delivery.ordinal)
          val stressConfig = SessionConfig.make(2, 8).toOption.get
          val key          = SubscriptionKey(s"pressure-$delivery")
          for
            starts   <- Ref.make(0)
            releases <- Ref.make(0)
            stream = (ZStream.fromZIO(starts.update(_ + 1)) *>
                       (ZStream.fromIterable(1 to 4).map(ComponentMessage.Value.apply) ++ ZStream.never))
                       .ensuring(releases.update(_ + 1))
            componentDefinition = definition()
            instance            = component(componentDefinition, s"pressure-$delivery")
            (env, _, _, _, _) <- environment(
                                   lifecycle,
                                   Map(componentDefinition.asInstanceOf[AnyRef] ->
                                     Subscription(key, delivery, stream))
                                 )
            guards <- Ref.make(0)
            render <- program(instance)
            kernel <- SessionKernel.start(
                        stressConfig,
                        logic(guards),
                        render,
                        Outbound(),
                        env,
                        providedLifecycle = Some(lifecycle)
                      )
            mounted <- retrySaturated(kernel.inspect).map(_.components.values.head)
            _       <- starts.get.repeatUntil(_ == 1)
            _ <- ZIO.foreachDiscard(1 to 3) { expected =>
                   retrySaturated(
                     kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
                   ) *>
                     retrySaturated(
                       kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Show))
                     ) *>
                     starts.get.repeatUntil(_ >= expected + 1).unit
                 }
            _ <- retrySaturated(
                   kernel.submitComponent(mounted.id, ComponentMessage.CancelAndHide(key))
                 )
            startCount <- starts.get
            _          <- releases.get.repeatUntil(_ == startCount)
            _ <- retrySaturated(
                   kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Show))
                 )
            _ <- retrySaturated(
                   kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Hide))
                 )
            _ <- retrySaturated(
                   kernel.submit(SessionCommand.Message(kernel.epoch, RootMessage.Show))
                 )
            committed   <- retrySaturated(kernel.inspect)
            releaseCount <- releases.get
          yield assertTrue(
            startCount == 4,
            releaseCount == startCount,
            committed.components.values.map(_.id) == Vector(mounted.id),
            committed.managedResources.values.isEmpty
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("failed and duplicate component starts never activate a candidate subscription") {
      enum InvalidStart:
        case FailedCandidate, Duplicate

      ZIO.foreach(InvalidStart.values.toVector) { invalid =>
        ZIO.scoped {
          val lifecycle = LifecycleId(250L + invalid.ordinal)
          val existingKey = SubscriptionKey("existing")
          for
            existingStarts <- Ref.make(0)
            candidateStarts <- Ref.make(0)
            existingStream  = ZStream.fromZIO(existingStarts.update(_ + 1)) *> ZStream.never
            candidateStream = ZStream.fromZIO(candidateStarts.update(_ + 1)) *> ZStream.never
            componentDefinition = definition()
            instance            = component(componentDefinition, s"invalid-$invalid")
            initial = invalid match
                        case InvalidStart.FailedCandidate => Map.empty[AnyRef, Subscription]
                        case InvalidStart.Duplicate       =>
                          Map(componentDefinition.asInstanceOf[AnyRef] ->
                            Subscription(existingKey, SubscriptionDelivery.Lossless, existingStream))
            (env, _, _, _, failAfterRender) <- environment(lifecycle, initial)
            guards <- Ref.make(0)
            render <- program(instance)
            afterRender = (draft: TurnDraft[RootMessage, Boolean]) =>
                            failAfterRender.get.flatMap { fail =>
                              if fail then ZIO.fail(IllegalStateException("candidate rejected"))
                              else ZIO.succeed(draft)
                            }
            kernel <- SessionKernel.start(
                        config,
                        logic(guards, afterRender),
                        render,
                        Outbound(),
                        env,
                        providedLifecycle = Some(lifecycle)
                      )
            mounted <- kernel.inspect.map(_.components.values.head)
            _ <- ZIO.when(invalid == InvalidStart.Duplicate)(
                   existingStarts.get.repeatUntil(_ == 1).unit
                 )
            candidate = Subscription(
                          existingKey,
                          SubscriptionDelivery.Latest,
                          candidateStream
                        )
            message = invalid match
                        case InvalidStart.FailedCandidate => ComponentMessage.StartThenFail(candidate)
                        case InvalidStart.Duplicate       => ComponentMessage.Duplicate(candidate)
            result <- kernel.submitComponent(mounted.id, message).either
            count  <- candidateStarts.get
          yield assertTrue(result.isLeft, count == 0)
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    }
  ) @@ TestAspect.timeout(10.seconds)
