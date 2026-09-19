package scalive.docs.examples

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.testing.ConnectedRender

object ConnectedResourceExampleSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    java.time.Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  override def spec = suite("ConnectedResourceExampleSpec")(
    test("route registration runs only before connected page mount and releases once on leave") {
      ZIO.scoped {
        for
          events <- Ref.make(Vector.empty[String])
          registrations = new LifecycleRegistrations:
                            def register(owner: String): UIO[LifecycleRegistration] =
                              events
                                .update(_ :+ s"acquire:$owner")
                                .as(LifecycleRegistration(owner))

                            def unregister(registration: LifecycleRegistration): UIO[Unit] =
                              events.update(_ :+ s"release:${registration.id}")
          page = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext): UIO[Unit] = ctx.connection match
                     case Connection.Disconnected => events.update(_ :+ "disconnected mount")
                     case Connection.Connected(_) => events.update(_ :+ "connected mount")

                   def view(model: Signal[Unit]) = div("Registered page")
          client <- ConnectedRender.open(
                      ConnectedResourceRouteExample.application(registrations, page),
                      config,
                      Request.get(URL.decode("/registered").toOption.get)
                    )
          afterDisconnected <- events.get
          connected         <- client.join
          afterConnected    <- events.get
          _                 <- connected.leave
          afterLeave        <- events.get
          _                 <- client.disconnect
          afterDisconnect   <- events.get
        yield assertTrue(
          afterDisconnected == Vector("disconnected mount"),
          afterConnected == Vector(
            "disconnected mount",
            "acquire:registered-page",
            "connected mount"
          ),
          afterLeave == (afterConnected :+ "release:registered-page"),
          afterDisconnect == afterLeave
        )
      }
    },
    test("returns visible handles and releases independent lifecycle registrations") {
      ZIO.scoped {
        for
          acquisitions <- Ref.make(Vector.empty[LifecycleRegistration])
          releases     <- Ref.make(Vector.empty[LifecycleRegistration])
          registrations = new LifecycleRegistrations:
                            def register(owner: String): UIO[LifecycleRegistration] =
                              acquisitions.modify { current =>
                                val registration =
                                  LifecycleRegistration(s"registration:$owner:${current.size + 1}")
                                registration -> (current :+ registration)
                              }

                            def unregister(registration: LifecycleRegistration): UIO[Unit] =
                              releases.update(_ :+ registration)
          first <- ConnectedRender.join(
                     new ConnectedResourceExample("first", registrations)
                   )
          second <- ConnectedRender.join(
                      new ConnectedResourceExample("second", registrations)
                    )
          firstHandle  <- first.text("[data-connected-resource-handle]")
          secondHandle <- second.text("[data-connected-resource-handle]")
          _            <- first.clickButton("Update model")
          firstChecks  <- first.text("[data-connected-resource-checks]")
          afterCheck   <- acquisitions.get
          _            <- first.leave
          afterFirst   <- releases.get
          _            <- second.leave
          afterSecond  <- releases.get
        yield assertTrue(
          firstHandle != secondHandle,
          firstChecks == "1",
          afterCheck.size == 2,
          afterFirst.map(_.id) == Vector(firstHandle),
          afterSecond.map(_.id).toSet == Set(firstHandle, secondHandle),
          afterSecond.size == 2
        )
      }
    },
    test("reset changes model state without reacquiring the registration") {
      ZIO.scoped {
        for
          acquisitions <- Ref.make(0)
          releases     <- Ref.make(0)
          registrations = new LifecycleRegistrations:
                            def register(owner: String): UIO[LifecycleRegistration] =
                              acquisitions.updateAndGet(_ + 1).map(number =>
                                LifecycleRegistration(s"registration:$owner:$number")
                              )

                            def unregister(registration: LifecycleRegistration): UIO[Unit] =
                              releases.update(_ + 1)
          connected <- ConnectedRender.join(
                         new ConnectedResourceExample("reset", registrations)
                       )
          _       <- connected.clickButton("Update model")
          _       <- connected.clickButton("Reset checks")
          checks  <- connected.text("[data-connected-resource-checks]")
          acquired <- acquisitions.get
          releasedBeforeLeave <- releases.get
          _                   <- connected.leave
          releasedAfterLeave  <- releases.get
        yield assertTrue(
          checks == "0",
          acquired == 1,
          releasedBeforeLeave == 0,
          releasedAfterLeave == 1
        )
      }
    }
  )
end ConnectedResourceExampleSpec
