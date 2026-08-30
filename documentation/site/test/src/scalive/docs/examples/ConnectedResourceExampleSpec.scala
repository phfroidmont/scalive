package scalive.docs.examples

import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object ConnectedResourceExampleSpec extends ZIOSpecDefault:
  override def spec = suite("ConnectedResourceExampleSpec")(
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
