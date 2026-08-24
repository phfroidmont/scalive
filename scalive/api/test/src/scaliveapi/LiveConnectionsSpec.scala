package scalive

import zio.*
import zio.test.*

object LiveConnectionsSpec extends ZIOSpecDefault:
  private final class TestBus[Id](
      subscribers: Ref[Vector[Id => UIO[Unit]]],
      published: Ref[Vector[Id]]
  ) extends LiveDisconnectBus[Id]:
    def publish(id: Id): Task[Unit] =
      published.update(_ :+ id) *>
        subscribers.get.flatMap(ZIO.foreachDiscard(_)(_(id)))

    def subscribe(onDisconnect: Id => UIO[Unit]): ZIO[Scope, Throwable, Unit] =
      subscribers.update(_ :+ onDisconnect) *>
        ZIO.addFinalizer(
          subscribers.update(_.filterNot(callback => callback.asInstanceOf[AnyRef] eq onDisconnect.asInstanceOf[AnyRef]))
        ).unit

    def subscriberCount: UIO[Int] = subscribers.get.map(_.size)

    def publications: UIO[Vector[Id]] = published.get

  private object TestBus:
    def make[Id]: UIO[TestBus[Id]] =
      for
        subscribers <- Ref.make(Vector.empty[Id => UIO[Unit]])
        published   <- Ref.make(Vector.empty[Id])
      yield TestBus(subscribers, published)

  private def counter: UIO[(UIO[Unit], UIO[Int])] =
    Ref.make(0).map(ref => ref.update(_ + 1) -> ref.get)

  def spec = suite("LiveConnectionsSpec")(
    test("signals every matching pending and committed transport, but not unrelated IDs") {
      for
        connections              <- LiveConnections.make[String](_ => ZIO.unit)
        (firstControl, firstGet)  <- counter
        (secondControl, secondGet) <- counter
        (otherControl, otherGet)  <- counter
        first <- connections.begin("session", new LiveConnections.ConnectionKey, firstControl)
        _     <- connections.begin("session", new LiveConnections.ConnectionKey, secondControl)
        _     <- connections.begin("other", new LiveConnections.ConnectionKey, otherControl)
        _     <- connections.commit(first.token)
        _     <- connections.disconnect("session")
        firstCount  <- firstGet
        secondCount <- secondGet
        otherCount  <- otherGet
      yield assertTrue(firstCount == 1, secondCount == 1, otherCount == 0)
    },
    test("absent and duplicate disconnects are successful and idempotent") {
      for
        connections      <- LiveConnections.make[String](_ => ZIO.unit)
        (control, count)  <- counter
        _                 <- connections.disconnect("absent")
        _                 <- connections.begin("session", new LiveConnections.ConnectionKey, control)
        _                 <- ZIO.foreachParDiscard(1 to 100)(_ => connections.disconnect("session"))
        invocations       <- count
      yield assertTrue(invocations == 1)
    },
    test("same transport and ID is idempotent while a different ID is rejected") {
      for
        connections     <- LiveConnections.make[String](_ => ZIO.unit)
        key              = new LiveConnections.ConnectionKey
        (control, _)     <- counter
        first            <- connections.begin("first", key, control)
        repeated         <- connections.begin("first", key, ZIO.dieMessage("replacement control ran"))
        conflicting      <- connections.begin("second", key, control).exit
      yield assertTrue(
        !first.bindingAlreadyExisted,
        repeated.bindingAlreadyExisted,
        repeated.token.eq(first.token),
        conflicting.isFailure
      )
    },
    test("rollback removes only pending registrations while removal handles committed ones") {
      for
        connections      <- LiveConnections.make[String](_ => ZIO.unit)
        (pending, pendingCount) <- counter
        (committed, committedCount) <- counter
        pendingAdmission <- connections.begin("session", new LiveConnections.ConnectionKey, pending)
        committedAdmission <- connections.begin("session", new LiveConnections.ConnectionKey, committed)
        _ <- connections.commit(committedAdmission.token)
        _ <- connections.rollback(pendingAdmission.token)
        _ <- connections.rollback(committedAdmission.token)
        _ <- connections.disconnectLocal("session")
        pendingInvocations   <- pendingCount
        committedInvocations <- committedCount
        _ <- connections.remove(committedAdmission.token)
        _ <- connections.disconnectLocal("session")
        afterRemoval <- committedCount
      yield assertTrue(pendingInvocations == 0, committedInvocations == 1, afterRemoval == 1)
    },
    test("stale rollback and removal tokens cannot remove a newer registration") {
      for
        connections     <- LiveConnections.make[String](_ => ZIO.unit)
        key              = new LiveConnections.ConnectionKey
        (oldControl, _)  <- counter
        (newControl, newCount) <- counter
        oldAdmission     <- connections.begin("session", key, oldControl)
        _                <- connections.rollback(oldAdmission.token)
        newAdmission     <- connections.begin("session", key, newControl)
        _                <- connections.rollback(oldAdmission.token)
        _                <- connections.remove(oldAdmission.token)
        _                <- connections.commit(newAdmission.token)
        _                <- connections.disconnectLocal("session")
        invocations      <- newCount
      yield assertTrue(!newAdmission.bindingAlreadyExisted, invocations == 1)
    },
    test("concurrent begin has exactly one owner and one exact token") {
      for
        connections <- LiveConnections.make[String](_ => ZIO.unit)
        key          = new LiveConnections.ConnectionKey
        admissions  <- ZIO.foreachPar(1 to 500)(_ => connections.begin("session", key, ZIO.unit))
        begun        = admissions.count(!_.bindingAlreadyExisted)
        token        = admissions.head.token
      yield assertTrue(begun == 1, admissions.forall(_.token.eq(token)))
    },
    test("local signaling precedes and survives failed publication") {
      for
        events      <- Ref.make(Vector.empty[String])
        connections <- LiveConnections.make[String](_ => events.update(_ :+ "publish") *> ZIO.fail(new Exception("down")))
        admission   <- connections.begin("session", new LiveConnections.ConnectionKey, events.update(_ :+ "local"))
        _           <- connections.commit(admission.token)
        result      <- connections.disconnect("session").exit
        observed    <- events.get
      yield assertTrue(result.isFailure, observed == Vector("local", "publish"))
    },
    test("distributed acquisition is subscribed and fans out to two independent nodes") {
      ZIO.scoped {
        for
          bus <- TestBus.make[String]
          environment = ZLayer.succeed[LiveDisconnectBus[String]](bus)
          nodeOne <- LiveConnections.distributed[String].build
            .provideSomeLayer[Scope](environment)
            .map(_.get[LiveConnections[String]])
          readyAfterOne <- bus.subscriberCount
          nodeTwo <- LiveConnections.distributed[String].build
            .provideSomeLayer[Scope](environment)
            .map(_.get[LiveConnections[String]])
          (oneControl, oneCount) <- counter
          (twoControl, twoCount) <- counter
          (unrelatedControl, unrelatedCount) <- counter
          _ <- nodeOne.begin("session", new LiveConnections.ConnectionKey, oneControl)
          _ <- nodeTwo.begin("session", new LiveConnections.ConnectionKey, twoControl)
          _ <- nodeTwo.begin("other", new LiveConnections.ConnectionKey, unrelatedControl)
          _ <- nodeOne.disconnect("session")
          _ <- bus.publish("session")
          firstInvocations <- oneCount
          secondInvocations <- twoCount
          unrelatedInvocations <- unrelatedCount
          published <- bus.publications
        yield assertTrue(
          readyAfterOne == 1,
          firstInvocations == 1,
          secondInvocations == 1,
          unrelatedInvocations == 0,
          published == Vector("session", "session")
        )
      }
    },
    test("registration and disconnect races never invoke a control more than once") {
      ZIO.foreachPar(1 to 250) { _ =>
        for
          connections <- LiveConnections.make[Int](_ => ZIO.unit)
          (control, count) <- counter
          admission <- connections.begin(1, new LiveConnections.ConnectionKey, control)
          _ <- connections.commit(admission.token)
          _ <- connections.disconnectLocal(1).raceFirst(connections.remove(admission.token))
          _ <- connections.disconnectLocal(1)
          invocations <- count
        yield invocations
      }.map(invocations => assertTrue(invocations.forall(count => count == 0 || count == 1)))
    },
    test("registration controls are not part of the application-visible API") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        package outside
        import scalive.*
        import zio.*
        def invalid(connections: LiveConnections[String]) =
          connections.begin("session", new LiveConnections.ConnectionKey, ZIO.unit)
      """)

      assertTrue(errors.nonEmpty)
    }
  ) @@ TestAspect.timeout(30.seconds)
end LiveConnectionsSpec
