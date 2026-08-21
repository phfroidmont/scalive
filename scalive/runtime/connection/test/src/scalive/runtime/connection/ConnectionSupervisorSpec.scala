package scalive.runtime.connection

import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*

object ConnectionSupervisorSpec extends ZIOSpecDefault:
  private val config   = ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(false, Map("test" -> Json.Str("exact")))

  private final case class Fixture(
    supervisor: ConnectionSupervisor,
    claims: Queue[NestedCredentialClaims])

  private def fixture: ZIO[Scope, Nothing, Fixture] =
    for
      claims <- Queue.bounded[NestedCredentialClaims](8)
      issuer = new NestedCredentialIssuer:
                 def issue(value: NestedCredentialClaims): UIO[IssuedNestedCredentials] =
                   claims.offer(value).as(
                     IssuedNestedCredentials(
                       NestedJoinCredential(s"join-${value.registration.value}"),
                       None
                     )
                   )
      supervisor <- ConnectionSupervisor.make(
                      config,
                      issuer,
                      applicationId => NestedTopic(s"nested:$applicationId")
                    )
    yield Fixture(supervisor, claims)

  private def startRoot[Msg, Model](
    fixture: Fixture,
    view: LiveView[Msg, Model],
    outputs: Queue[ConnectionOutput],
    requestedLifecycle: Option[LifecycleId] = None
  ): IO[ConnectionSupervisor.StartError, ConnectedLifecycle] =
    fixture.supervisor.startRootLifecycle(
      RootLifecycle.ordinary(view, URL.root),
      metadata,
      "root",
      NestedTopic("root:main"),
      loading = false,
      outputs.offer(_).unit,
      requestedLifecycle = requestedLifecycle
    )

  private def claimsFor(fixture: Fixture): UIO[NestedCredentialClaims] = fixture.claims.take

  private def bindingFrom(output: ConnectionOutput): BindingId = output match
    case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) =>
      val encoded = tree.root.attributes
        .flatMap(_.value).collectFirst { case AttributeValue.Text(value) => value }.get
      BindingId.fromEncoded(encoded)
    case other => throw AssertionError(s"expected joined replacement, got $other")

  override def spec = suite("ConnectionSupervisorSpec")(
    test("a committed unjoined requirement does not construct its child") {
      ZIO.scoped {
        val constructions = AtomicInteger(0)
        object Child extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] =
            div(liveView("child", {
              constructions.incrementAndGet()
              Child
            }))

        for
          value   <- fixture
          outputs <- Queue.bounded[ConnectionOutput](4)
          _       <- startRoot(value, Parent, outputs)
          _       <- outputs.take
          claims  <- claimsFor(value)
        yield assertTrue(constructions.get() == 0, claims.topic == NestedTopic("nested:child"))
      }
    },
    test("an exact join constructs and independently mounts once") {
      ZIO.scoped {
        val constructions = AtomicInteger(0)
        for
          mounts <- Ref.make(0)
          value  <- fixture
          rootOutputs  <- Queue.bounded[ConnectionOutput](4)
          childOutputs <- Queue.bounded[ConnectionOutput](4)
          child = new LiveView.Eventless[Unit]:
                    def mount(ctx: MountContext): LiveIO[Unit] = mounts.update(_ + 1)
                    def view(model: Signal[Unit]): HtmlElement[Nothing] = div("child")
          parent = new LiveView.Eventless[Unit]:
                     def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
                     def view(model: Signal[Unit]): HtmlElement[Nothing] =
                       div(liveView("child", {
                         constructions.incrementAndGet()
                         child
                       }))
          _           <- startRoot(value, parent, rootOutputs)
          _           <- rootOutputs.take
          exactClaims <- claimsFor(value)
          reservation <- value.supervisor.reserveNested(exactClaims)
          nested <- value.supervisor.startNested(
                      reservation,
                      URL.root,
                      metadata,
                      "child-dom",
                      loading = true,
                      childOutputs.offer(_).unit
                    )
          joined     <- childOutputs.take
          mountCount <- mounts.get
          routed     <- value.supervisor.lifecycleForTopic(exactClaims.topic)
        yield assertTrue(
          constructions.get() == 1,
          mountCount == 1,
          joined.isInstanceOf[ConnectionOutput.Joined],
          routed.contains(nested)
        )
      }
    },
    test("a revoked reservation constructs and publishes nothing") {
      ZIO.scoped {
        val constructions = AtomicInteger(0)
        object Child extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
        val parent = new LiveView[Unit, Boolean]:
          def mount(ctx: MountContext): LiveIO[Boolean] = ZIO.succeed(true)
          def handleMessage(model: Boolean, ctx: MessageContext): Unit => LiveIO[Boolean] =
            _ => ZIO.succeed(false)
          def view(model: Signal[Boolean]): HtmlElement[Unit] =
            button(
              on.click(()),
              model.when(
                div(
                  liveView("child", {
                    constructions.incrementAndGet()
                    Child
                  })
                )
              )
            )

        for
          value        <- fixture
          rootOutputs  <- Queue.bounded[ConnectionOutput](4)
          childOutputs <- Queue.bounded[ConnectionOutput](4)
          root         <- startRoot(value, parent, rootOutputs)
          joined       <- rootOutputs.take
          exactClaims  <- claimsFor(value)
          reservation  <- value.supervisor.reserveNested(exactClaims)
          command = CommandId.fresh().toOption.get
          _ <- root.browserEvent(command, bindingFrom(joined), BindingPayload.Params(Map.empty))
          _ <- rootOutputs.take
          result <- value.supervisor
                      .startNested(
                        reservation,
                        URL.root,
                        metadata,
                        "stale-child",
                        loading = false,
                        childOutputs.offer(_).unit
                      ).either
          output <- childOutputs.poll
          lookup <- value.supervisor.lifecycleForTopic(exactClaims.topic)
        yield assertTrue(result.isLeft, constructions.get() == 0, output.isEmpty, lookup.isEmpty)
      }
    },
    test("unknown topic routing allocates nothing") {
      ZIO.scoped {
        val constructions = AtomicInteger(0)
        object Child extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] =
            div(liveView("child", {
              constructions.incrementAndGet()
              Child
            }))
        for
          value   <- fixture
          outputs <- Queue.bounded[ConnectionOutput](4)
          _       <- startRoot(value, Parent, outputs)
          _       <- outputs.take
          routed <- value.supervisor.routePatch(
                      NestedTopic("unknown"),
                      CommandId.fresh().toOption.get,
                      URL.root
                    )
          left <- value.supervisor.routeLeave(NestedTopic("unknown"))
        yield assertTrue(
          !routed,
          left == ConnectionSupervisor.LeaveResult.UnknownTopic,
          constructions.get() == 0
        )
      }
    },
    test("default mount failure is isolated while linked runtime failure closes the exact parent") {
      ZIO.scoped {
        def parent(linked: Boolean, failMount: Boolean) = new LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] =
            val child = new LiveView[Unit, Unit]:
              def mount(ctx: MountContext): LiveIO[Unit] =
                if failMount then ZIO.fail(Exception("mount failed")) else ZIO.unit
              def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
                _ => ZIO.fail(Exception("runtime failed"))
              def view(model: Signal[Unit]): HtmlElement[Unit] = button(on.click(()))
            div(
              liveView(
                "child",
                child,
                sticky = false,
                linkParentOnCrash = linked
              )
            )

        for
          isolated       <- fixture
          isolatedOutput <- Queue.bounded[ConnectionOutput](4)
          isolatedRoot   <- startRoot(isolated, parent(false, failMount = true), isolatedOutput)
          _              <- isolatedOutput.take
          isolatedClaims <- claimsFor(isolated)
          isolatedJoin   <- isolated.supervisor.reserveNested(isolatedClaims)
          isolatedResult <- isolated.supervisor
                              .startNested(
                                isolatedJoin,
                                URL.root,
                                metadata,
                                "isolated",
                                false,
                                _ => ZIO.unit
                              ).either
          rootStillActive <- isolated.supervisor.lifecycle(
                               isolatedRoot.lifecycle,
                               isolatedRoot.epoch
                             )
          linked       <- fixture
          linkedOutput <- Queue.bounded[ConnectionOutput](4)
          childOutput  <- Queue.bounded[ConnectionOutput](4)
          notified     <- Promise.make[Nothing, Boolean]
          linkedRoot   <- startRoot(linked, parent(true, failMount = false), linkedOutput)
          _            <- linkedOutput.take
          linkedClaims <- claimsFor(linked)
          linkedJoin   <- linked.supervisor.reserveNested(linkedClaims)
          notifier = ConnectionSupervisor.NestedFailureNotifier(
                       onStart = _ => ZIO.unit,
                       onRuntime = _ =>
                         linked.supervisor
                           .lifecycle(linkedRoot.lifecycle, linkedRoot.epoch).flatMap(active =>
                             notified.succeed(active.contains(linkedRoot)).unit
                           )
                     )
          linkedChild <- linked.supervisor.startNested(
                           linkedJoin,
                           URL.root,
                           metadata,
                           "linked",
                           false,
                           childOutput.offer(_).unit,
                           notifier
                          )
          childBootstrap <- childOutput.take
          childBinding = bindingFrom(childBootstrap)
          linkedResult <- linkedChild
                             .browserEvent(
                               CommandId.fresh().toOption.get,
                               childBinding,
                               BindingPayload.Params(Map.empty)
                               ).either
          _ <- linked.supervisor.retireTerminatedLifecycle(linkedChild)
          parentActiveWhenNotified <- notified.await
          _ <- linkedRoot.awaitClosed
          linkedFailure <- linkedRoot.pollFailure
        yield assertTrue(
          isolatedResult.isLeft,
          rootStillActive.contains(isolatedRoot),
          linkedResult.isRight,
          parentActiveWhenNotified,
          linkedFailure.exists(_.isInstanceOf[ConnectionError.LinkedChildFailed])
        )
      }
    },
    test("linked mount failure notifies before closing the exact parent") {
      ZIO.scoped {
        object Child extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.fail(Exception("mount failed"))
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] =
            div(liveView("child", Child, linkParentOnCrash = true))

        for
          value      <- fixture
          rootOutput <- Queue.bounded[ConnectionOutput](4)
          childOutput <- Queue.bounded[ConnectionOutput](4)
          notified   <- Promise.make[Nothing, Boolean]
          root       <- startRoot(value, Parent, rootOutput)
          _          <- rootOutput.take
          claims     <- claimsFor(value)
          reservation <- value.supervisor.reserveNested(claims)
          notifier = ConnectionSupervisor.NestedFailureNotifier(
                       onStart = _ =>
                         value.supervisor.lifecycle(root.lifecycle, root.epoch).flatMap(active =>
                           notified.succeed(active.contains(root)).unit
                         ),
                       onRuntime = _ => ZIO.unit
                     )
          result <- value.supervisor
                      .startNested(
                        reservation,
                        URL.root,
                        metadata,
                        "linked",
                        false,
                        childOutput.offer(_).unit,
                        notifier
                      ).either
          parentActiveWhenNotified <- notified.await
          _                        <- root.awaitClosed
          rootFailure              <- root.pollFailure
        yield assertTrue(
          result.swap.exists(
            _.isInstanceOf[ConnectionSupervisor.StartError.LinkedConnectionFailed]
          ),
          parentActiveWhenNotified,
          rootFailure.exists(_.isInstanceOf[ConnectionError.LinkedChildJoinFailed])
        )
      }
    },
    test("an unlinked crashed child can remount from its retained registration") {
      ZIO.scoped {
        for
          mounts       <- Ref.make(0)
          value        <- fixture
          rootOutput   <- Queue.bounded[ConnectionOutput](4)
          childOutput  <- Queue.bounded[ConnectionOutput](4)
          secondOutput <- Queue.bounded[ConnectionOutput](4)
          child = new LiveView[Unit, Int]:
                    def mount(ctx: MountContext): LiveIO[Int] = mounts.updateAndGet(_ + 1)
                    def handleMessage(model: Int, ctx: MessageContext): Unit => LiveIO[Int] =
                      _ => ZIO.fail(Exception("runtime failed"))
                    def view(model: Signal[Int]): HtmlElement[Unit] = button(on.click(()))
          parent = new LiveView.Eventless[Unit]:
                     def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
                     def view(model: Signal[Unit]): HtmlElement[Nothing] =
                       div(liveView("child", child))
          root         <- startRoot(value, parent, rootOutput)
          _            <- rootOutput.take
          exactClaims  <- claimsFor(value)
          firstJoin    <- value.supervisor.reserveNested(exactClaims)
          first <- value.supervisor.startNested(
                     firstJoin,
                     URL.root,
                     metadata,
                     "child",
                     false,
                     childOutput.offer(_).unit
                   )
          firstJoined <- childOutput.take
          _ <- first.browserEvent(
                 CommandId.fresh().toOption.get,
                 bindingFrom(firstJoined),
                 BindingPayload.Params(Map.empty)
               )
          _ <- first.awaitClosed
          _ <- value.supervisor.retireLifecycle(first)
          secondJoin <- value.supervisor.reserveNested(exactClaims)
          second <- value.supervisor.startNested(
                      secondJoin,
                      URL.root,
                      metadata,
                      "child",
                      false,
                      secondOutput.offer(_).unit
                    )
          _          <- secondOutput.take
          mountCount <- mounts.get
          rootActive <- value.supervisor.lifecycle(root.lifecycle, root.epoch)
        yield assertTrue(
          second.lifecycle != first.lifecycle,
          mountCount == 2,
          rootActive.contains(root)
        )
      }
    },
    test("a stale retirement wait ignores a replacement with reused coordinates") {
      ZIO.scoped {
        object Root extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div("root")

        val lifecycle = LifecycleId(500L)
        for
          value        <- fixture
          firstOutput  <- Queue.bounded[ConnectionOutput](4)
          secondOutput <- Queue.bounded[ConnectionOutput](4)
          first        <- startRoot(value, Root, firstOutput, Some(lifecycle))
          _            <- firstOutput.take
          _            <- value.supervisor.routeNavigationLeave(first.topic)
          _            <- first.awaitClosed
          second       <- startRoot(value, Root, secondOutput, Some(lifecycle))
          _            <- secondOutput.take
          _            <- value.supervisor.awaitRetirement(first)
          active       <- value.supervisor.lifecycle(second.lifecycle, second.epoch)
        yield assertTrue(active.contains(second))
      }
    },
    test("compatible navigation reattaches an exact sticky child without remounting") {
      ZIO.scoped {
        val constructions = AtomicInteger(0)
        for
          mounts <- Ref.make(0)
          value  <- fixture
          firstRootOutput  <- Queue.bounded[ConnectionOutput](4)
          secondRootOutput <- Queue.bounded[ConnectionOutput](4)
          thirdRootOutput  <- Queue.bounded[ConnectionOutput](4)
          firstChildOutput <- Queue.bounded[ConnectionOutput](4)
          secondChildOutput <- Queue.bounded[ConnectionOutput](4)
          thirdChildOutput <- Queue.bounded[ConnectionOutput](4)
          child = new LiveView[Unit, Int]:
                    def mount(ctx: MountContext): LiveIO[Int] = mounts.updateAndGet(_ + 1).as(0)
                    def handleMessage(model: Int, ctx: MessageContext): Unit => LiveIO[Int] =
                      _ => ZIO.succeed(model + 1)
                    def view(model: Signal[Int]): HtmlElement[Unit] =
                      button(on.click(()), model.map(_.toString))
          parent = new LiveView.Eventless[Unit]:
                     def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
                     def view(model: Signal[Unit]): HtmlElement[Nothing] =
                       div(liveView("sticky-child", {
                         constructions.incrementAndGet()
                         child
                       }, sticky = true))
          nonStickyParent = new LiveView.Eventless[Unit]:
                               def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
                               def view(model: Signal[Unit]): HtmlElement[Nothing] =
                                 div(liveView("sticky-child", {
                                   constructions.incrementAndGet()
                                   child
                                 }))
          firstRoot    <- startRoot(value, parent, firstRootOutput)
          _            <- firstRootOutput.take
          firstClaims  <- claimsFor(value)
          firstJoin    <- value.supervisor.reserveNested(firstClaims)
          firstChild <- value.supervisor.startNested(
                          firstJoin,
                          URL.root,
                          metadata,
                          "sticky-child",
                          false,
                          firstChildOutput.offer(_).unit
                        )
          firstJoined <- firstChildOutput.take
          _ <- firstChild.browserEvent(
                 CommandId.fresh().toOption.get,
                 bindingFrom(firstJoined),
                 BindingPayload.Params(Map.empty)
               )
          _      <- firstChildOutput.take
          leave  <- value.supervisor.routeNavigationLeave(NestedTopic("root:main"))
          _      <- firstRoot.awaitClosed
          channelLeave <- value.supervisor.routeLeave(firstClaims.topic)
          active <- value.supervisor.lifecycle(firstChild.lifecycle, firstChild.epoch)
          secondRoot   <- startRoot(value, parent, secondRootOutput)
          _            <- secondRootOutput.take
          secondClaims <- claimsFor(value)
          secondJoin   <- value.supervisor.reserveNested(secondClaims)
          reattached <- value.supervisor.startNested(
                          secondJoin,
                          URL.root,
                          metadata,
                          "sticky-child",
                          false,
                          secondChildOutput.offer(_).unit,
                          reattach = true
                        )
          rejoined   <- secondChildOutput.take
          mountCount <- mounts.get
          secondNavigation <- value.supervisor.routeNavigationLeave(NestedTopic("root:main"))
          _                <- secondRoot.awaitClosed
          secondChannelLeave <- value.supervisor.routeLeave(secondClaims.topic)
          detached           <- value.supervisor.lifecycle(firstChild.lifecycle, firstChild.epoch)
          _           <- startRoot(value, nonStickyParent, thirdRootOutput)
          _           <- thirdRootOutput.take
          thirdClaims <- claimsFor(value)
          thirdJoin   <- value.supervisor.reserveNested(thirdClaims)
          fresh <- value.supervisor.startNested(
                     thirdJoin,
                     URL.root,
                     metadata,
                     "sticky-child",
                     false,
                     thirdChildOutput.offer(_).unit
                   )
          _               <- thirdChildOutput.take
          _               <- firstChild.awaitClosed
          finalMountCount <- mounts.get
          _          <- value.supervisor.close
          _          <- fresh.awaitClosed
        yield assertTrue(
          leave == ConnectionSupervisor.LeaveResult.Left,
          channelLeave == ConnectionSupervisor.LeaveResult.Left,
          active.contains(firstChild),
          reattached eq firstChild,
          mountCount == 1,
          rejoined.isInstanceOf[ConnectionOutput.Joined],
          secondNavigation == ConnectionSupervisor.LeaveResult.Left,
          secondChannelLeave == ConnectionSupervisor.LeaveResult.Left,
          detached.contains(firstChild),
          fresh.lifecycle != firstChild.lifecycle,
          constructions.get() == 2,
          finalMountCount == 2
        )
      }
    },
    test("child leave closes descendants and supervisor close closes every remaining lifecycle") {
      ZIO.scoped {
        object Grandchild extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div("grandchild")
        object Child extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] =
            div(liveView("grandchild", Grandchild))
        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
          def view(model: Signal[Unit]): HtmlElement[Nothing] = div(liveView("child", Child))

        for
          value       <- fixture
          rootOutput  <- Queue.bounded[ConnectionOutput](4)
          childOutput <- Queue.bounded[ConnectionOutput](4)
          grandOutput <- Queue.bounded[ConnectionOutput](4)
          root        <- startRoot(value, Parent, rootOutput)
          _           <- rootOutput.take
          childClaims <- claimsFor(value)
          childJoin   <- value.supervisor.reserveNested(childClaims)
          child <- value.supervisor.startNested(
                     childJoin,
                     URL.root,
                     metadata,
                     "child",
                     false,
                     childOutput.offer(_).unit
                   )
          _           <- childOutput.take
          grandClaims <- claimsFor(value)
          grandJoin   <- value.supervisor.reserveNested(grandClaims)
          grand <- value.supervisor.startNested(
                     grandJoin,
                     URL.root,
                     metadata,
                     "grandchild",
                     false,
                     grandOutput.offer(_).unit
                   )
          _     <- grandOutput.take
          leave <- value.supervisor.routeLeave(childClaims.topic)
          _     <- child.awaitClosed
          _     <- grand.awaitClosed
          grandLookup <- value.supervisor.lifecycle(grand.lifecycle, grand.epoch)
          _           <- value.supervisor.close
          _           <- value.supervisor.close
          _           <- root.awaitClosed
        yield assertTrue(
          leave == ConnectionSupervisor.LeaveResult.Left,
          grandLookup.isEmpty
        )
      }
    },
    test("cleanup defects cannot suppress remaining lifecycle closure") {
      ZIO.scoped {
        for
          rootFinalized <- Promise.make[Nothing, Unit]
          rootStarted   <- Promise.make[Nothing, Unit]
          childStarted  <- Promise.make[Nothing, Unit]
          value         <- fixture
          rootOutput    <- Queue.bounded[ConnectionOutput](4)
          childOutput   <- Queue.bounded[ConnectionOutput](4)
          child = new LiveView.Eventless[Unit]:
                    def mount(ctx: MountContext): LiveIO[Unit] =
                      ctx.connection match
                        case Connection.Connected(connected) =>
                          connected.subscriptions.start(
                            SubscriptionKey("defect"),
                            SubscriptionDelivery.Lossless
                          )(
                            (ZStream.fromZIO(childStarted.succeed(()).unit) *> ZStream.never)
                              .ensuring(ZIO.dieMessage("child cleanup defect"))
                          )
                        case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
          parent = new LiveView.Eventless[Unit]:
                     def mount(ctx: MountContext): LiveIO[Unit] =
                       ctx.connection match
                         case Connection.Connected(connected) =>
                           connected.subscriptions.start(
                             SubscriptionKey("root"),
                             SubscriptionDelivery.Lossless
                           )(
                             (ZStream.fromZIO(rootStarted.succeed(()).unit) *> ZStream.never)
                               .ensuring(rootFinalized.succeed(()).unit)
                           )
                         case Connection.Disconnected => ZIO.dieMessage("expected connected mount")
                     def view(model: Signal[Unit]): HtmlElement[Nothing] =
                       div(liveView("child", child))
          root        <- startRoot(value, parent, rootOutput)
          _           <- rootOutput.take
          childClaims <- claimsFor(value)
          reservation <- value.supervisor.reserveNested(childClaims)
          nested <- value.supervisor.startNested(
                      reservation,
                      URL.root,
                      metadata,
                      "child",
                      false,
                      childOutput.offer(_).unit
                    )
          _         <- childOutput.take
          _         <- rootStarted.await *> childStarted.await
          closeExit <- value.supervisor.close.exit
          rootClosed <- root.awaitClosed.timeout(1.second)
          childClosed <- nested.awaitClosed.timeout(1.second)
          rootRan    <- rootFinalized.isDone
        yield assertTrue(
          closeExit.isSuccess,
          rootClosed.nonEmpty,
          childClosed.nonEmpty,
          rootRan
        )
      }
    }
  )
