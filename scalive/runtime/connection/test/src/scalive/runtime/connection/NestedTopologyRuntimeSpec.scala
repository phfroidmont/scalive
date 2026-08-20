package scalive.runtime.connection

import zio.Ref
import zio.ZIO
import zio.test.*

import scalive.*
import scalive.runtime.contracts.*

object NestedTopologyRuntimeSpec extends ZIOSpecDefault:
  private object ChildView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private val parent      = LifecycleId(100L)
  private val parentEpoch = Epoch.initial

  private def requirement(applicationId: String, sticky: Boolean = false) =
    NestedLifecycleRequirement(
      applicationId,
      sticky,
      linkParentOnCrash = true,
      NestedLifecycleFactory(() => ChildView)
    )

  private final case class Fixture(
    runtime: NestedTopologyRuntime,
    issuedClaims: Ref[Vector[NestedCredentialClaims]],
    retired: Ref[Vector[LifecycleId]])

  private def fixture: ZIO[Any, Nothing, Fixture] =
    for
      claims  <- Ref.make(Vector.empty[NestedCredentialClaims])
      retired <- Ref.make(Vector.empty[LifecycleId])
      issuer = new NestedCredentialIssuer:
                 def issue(value: NestedCredentialClaims) =
                   claims.update(_ :+ value).as(
                     IssuedNestedCredentials(
                       NestedJoinCredential(s"join-${value.registration.value}"),
                       Some(NestedStaticCredential(s"static-${value.registration.value}"))
                     )
                   )
      runtime <- NestedTopologyRuntime.make(
                   issuer,
                   applicationId => NestedTopic(s"nested:$applicationId"),
                   lifecycle => retired.update(_ :+ lifecycle)
                 )
    yield Fixture(runtime, claims, retired)

  private def prepare(
    fixture: Fixture,
    requirements: Vector[NestedLifecycleRequirement],
    lifecycle: LifecycleId = parent,
    epoch: Epoch = parentEpoch,
    revision: TurnRevision = TurnRevision(1L)
  ): ZIO[Any, NestedTopologyError, PreparedNestedTopology] =
    fixture.runtime
      .preparer("parent-dom", loading = true)
      .prepare(lifecycle, epoch, revision, requirements)

  private def claims(resolution: NestedRegistrationResolution): NestedCredentialClaims =
    NestedCredentialClaims(
      resolution.registration,
      NestedRegistrationEpoch.initial,
      parent,
      parentEpoch,
      resolution.topic
    )

  override def spec = suite("NestedTopologyRuntimeSpec")(
    test("preparation is inactive and credentials contain exact allocated claims") {
      for
        fixture <- fixture
        prepared <- prepare(fixture, Vector(requirement("chat")))
        resolution = prepared.resolutions.head
        before <- fixture.runtime.registration(resolution.registration)
        issued <- fixture.issuedClaims.get
      yield assertTrue(
        before.isEmpty,
        prepared.resolutions.map(_.applicationId) == Vector("chat"),
        issued == Vector(
          NestedCredentialClaims(
            resolution.registration,
            NestedRegistrationEpoch.initial,
            parent,
            parentEpoch,
            NestedTopic("nested:chat")
          )
        )
      )
    },
    test("disconnected bootstrap claims resolve only the exact active coordinates") {
      for
        fixture  <- fixture
        prepared <- prepare(fixture, Vector(requirement("chat")))
        _        <- prepared.activate
        active   <- fixture.issuedClaims.get.map(_.head)
        bootstrap = active.copy(
                      registration = NestedRegistrationId(active.registration.value + 1000000L),
                      childLifecycle = Some(LifecycleId(101L))
                    )
        admitted <- fixture.runtime.reserveJoin(bootstrap).either
        _ <- admitted match
               case Right(reservation) => fixture.runtime.cancelJoin(reservation)
               case Left(_)            => ZIO.unit
        stale <- fixture.runtime
                   .reserveJoin(bootstrap.copy(registrationEpoch = NestedRegistrationEpoch(2L))).either
        wrongParent <- fixture.runtime
                         .reserveJoin(bootstrap.copy(parentLifecycle = LifecycleId(999L))).either
      yield assertTrue(
        admitted.exists(_.registration.id == active.registration),
        stale.isLeft,
        wrongParent.isLeft
      )
    },
    test("seeded disconnected child lifecycle is carried by the initial credentials") {
      val childLifecycle = LifecycleId(101L)
      for
        fixture <- fixture
        _       <- fixture.runtime.seedChildLifecycles(parent, Map("chat" -> childLifecycle))
        _       <- prepare(fixture, Vector(requirement("chat")))
        claims  <- fixture.issuedClaims.get.map(_.head)
      yield assertTrue(claims.childLifecycle.contains(childLifecycle))
    },
    test("retained registrations preserve token and credentials") {
      for
        fixture <- fixture
        first <- prepare(fixture, Vector(requirement("chat", sticky = true)))
        _     <- first.activate
        second <- prepare(
                    fixture,
                    Vector(requirement("chat", sticky = true)),
                    revision = TurnRevision(2L)
                  )
        count <- fixture.issuedClaims.get.map(_.size)
        firstResolution  = first.resolutions.head
        secondResolution = second.resolutions.head
      yield assertTrue(
        firstResolution.registration == secondResolution.registration,
        firstResolution.instanceToken eq secondResolution.instanceToken,
        firstResolution.joinCredential == secondResolution.joinCredential,
        firstResolution.staticCredential == secondResolution.staticCredential,
        count == 1
      )
    },
    test("release wins before activation") {
      for
        fixture <- fixture
        prepared <- prepare(fixture, Vector(requirement("released")))
        _        <- prepared.release
        _        <- prepared.activate
        active   <- fixture.runtime.activeRegistrations
      yield assertTrue(active.isEmpty)
    },
    test("activation makes registrations visible") {
      for
        fixture <- fixture
        prepared <- prepare(fixture, Vector(requirement("visible")))
        _        <- prepared.activate
        active   <- fixture.runtime.registration(prepared.resolutions.head.registration)
      yield assertTrue(active.exists(_.applicationId == "visible"))
    },
    test("activation merges a delayed attachment to a retained registration") {
      for
        fixture <- fixture
        initial <- prepare(fixture, Vector(requirement("retained", sticky = true)))
        _       <- initial.activate
        next <- prepare(
                  fixture,
                  Vector(requirement("retained", sticky = true)),
                  revision = TurnRevision(2L)
                )
        reservation <- fixture.runtime.reserveJoin(claims(initial.resolutions.head))
        child = LifecycleId(200L)
        attached <- fixture.runtime.completeJoin(reservation, child, Epoch(2L))
        _        <- next.activate
        actual   <- fixture.runtime.attachedChild(initial.resolutions.head.registration)
      yield assertTrue(attached, actual.exists(_.lifecycle == child))
    },
    test("compatible activation keeps an admitted reservation current") {
      for
        fixture <- fixture
        initial <- prepare(fixture, Vector(requirement("admitted", sticky = true)))
        _       <- initial.activate
        reservation <- fixture.runtime.reserveJoin(claims(initial.resolutions.head))
        next <- prepare(
                  fixture,
                  Vector(requirement("admitted", sticky = true)),
                  revision = TurnRevision(2L)
                )
        _       <- next.activate
        current <- fixture.runtime.beginJoin(reservation)
        attached <- fixture.runtime.completeJoin(
                      reservation,
                      LifecycleId(220L),
                      Epoch.initial
                    )
      yield assertTrue(current, attached)
    },
    test("replacement revokes a pending reservation before completion") {
      for
        fixture <- fixture
        initial <- prepare(fixture, Vector(requirement("replace")))
        _       <- initial.activate
        reservation <- fixture.runtime.reserveJoin(claims(initial.resolutions.head))
        replacement <- prepare(fixture, Vector(requirement("replace", sticky = true)))
        _            <- replacement.activate
        completed <- fixture.runtime.completeJoin(reservation, LifecycleId(201L), Epoch.initial)
      yield assertTrue(!completed)
    },
    test("duplicate joins and attached joins are rejected deterministically") {
      for
        fixture <- fixture
        prepared <- prepare(fixture, Vector(requirement("duplicate")))
        _        <- prepared.activate
        exactClaims = claims(prepared.resolutions.head)
        reservation <- fixture.runtime.reserveJoin(exactClaims)
        duplicate   <- fixture.runtime.reserveJoin(exactClaims).either
        _ <- fixture.runtime.completeJoin(reservation, LifecycleId(202L), Epoch.initial)
        attached <- fixture.runtime.reserveJoin(exactClaims).either
      yield assertTrue(
        duplicate == Left(
          NestedJoinAdmissionError.RegistrationAlreadyPending(prepared.resolutions.head.registration)
        ),
        attached == Left(
          NestedJoinAdmissionError.RegistrationAlreadyAttached(prepared.resolutions.head.registration)
        )
      )
    },
    test("detaching an exact child keeps its registration joinable") {
      for
        fixture  <- fixture
        prepared <- prepare(fixture, Vector(requirement("detach")))
        _        <- prepared.activate
        exactClaims = claims(prepared.resolutions.head)
        first <- fixture.runtime.reserveJoin(exactClaims)
        child = LifecycleId(212L)
        _        <- fixture.runtime.completeJoin(first, child, Epoch.initial)
        detached <- fixture.runtime.detachChild(first.registration.id, child, Epoch.initial)
        second   <- fixture.runtime.reserveJoin(exactClaims)
        _        <- fixture.runtime.cancelJoin(second)
      yield assertTrue(detached.exists(_.applicationId == "detach"))
    },
    test("old claims are stale after replacement") {
      for
        fixture <- fixture
        initial <- prepare(fixture, Vector(requirement("stale")))
        _       <- initial.activate
        oldClaims = claims(initial.resolutions.head)
        replacement <- prepare(fixture, Vector(requirement("stale", sticky = true)))
        _            <- replacement.activate
        stale        <- fixture.runtime.reserveJoin(oldClaims).either
      yield assertTrue(
        stale == Left(
          NestedJoinAdmissionError.RegistrationUnavailable(initial.resolutions.head.registration)
        )
      )
    },
    test("activation defers retirement until retire and runs it once") {
      for
        fixture <- fixture
        initial <- prepare(fixture, Vector(requirement("removed")))
        _       <- initial.activate
        reservation <- fixture.runtime.reserveJoin(claims(initial.resolutions.head))
        child = LifecycleId(203L)
        _       <- fixture.runtime.completeJoin(reservation, child, Epoch.initial)
        removal <- prepare(fixture, Vector.empty)
        _       <- removal.activate
        before  <- fixture.retired.get
        _       <- removal.release
        _       <- removal.retire
        _       <- removal.retire
        after   <- fixture.retired.get
      yield assertTrue(before.isEmpty, after == Vector(child))
    },
    test("parent revocation recursively retires attached descendants") {
      for
        fixture <- fixture
        root <- prepare(fixture, Vector(requirement("root")))
        _    <- root.activate
        rootReservation <- fixture.runtime.reserveJoin(claims(root.resolutions.head))
        child = LifecycleId(204L)
        childEpoch = Epoch(4L)
        _ <- fixture.runtime.completeJoin(rootReservation, child, childEpoch)
        nested <- prepare(fixture, Vector(requirement("nested")), child, childEpoch)
        _      <- nested.activate
        nestedRegistration <- fixture.runtime.registration(nested.resolutions.head.registration)
        nestedClaims = NestedCredentialClaims(
                         nested.resolutions.head.registration,
                         nestedRegistration.get.epoch,
                         child,
                         childEpoch,
                         nested.resolutions.head.topic
                       )
        nestedReservation <- fixture.runtime.reserveJoin(nestedClaims)
        grandchild = LifecycleId(205L)
        _ <- fixture.runtime.completeJoin(nestedReservation, grandchild, Epoch.initial)
        _ <- fixture.runtime.revokeParent(parent, parentEpoch)
        active  <- fixture.runtime.activeRegistrations
        retired <- fixture.retired.get
      yield assertTrue(active.isEmpty, retired == Vector(child, grandchild))
    },
    test("close retires all attached lifecycles exactly once") {
      for
        fixture <- fixture
        prepared <- prepare(fixture, Vector(requirement("one"), requirement("two")))
        _        <- prepared.activate
        registrations <- fixture.runtime.activeRegistrations
        _ <- ZIO.foreachDiscard(registrations.zipWithIndex) { case (registration, index) =>
               val exactClaims = NestedCredentialClaims(
                 registration.id,
                 registration.epoch,
                 registration.parentLifecycle,
                 registration.parentEpoch,
                 registration.topic
               )
               fixture.runtime.reserveJoin(exactClaims).flatMap { reservation =>
                 fixture.runtime
                   .completeJoin(reservation, LifecycleId(210L + index), Epoch.initial)
                   .unit
               }
             }
        _       <- fixture.runtime.close
        _       <- fixture.runtime.close
        retired <- fixture.retired.get
        active  <- fixture.runtime.activeRegistrations
      yield assertTrue(retired == Vector(LifecycleId(210L), LifecycleId(211L)), active.isEmpty)
    }
  )
