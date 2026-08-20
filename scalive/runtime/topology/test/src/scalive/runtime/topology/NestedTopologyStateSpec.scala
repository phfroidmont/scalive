package scalive.runtime.topology

import zio.ZIO
import zio.test.*

import scalive.*
import scalive.runtime.contracts.*

object NestedTopologyStateSpec extends ZIOSpecDefault:
  private object FirstView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private object SecondView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private val parent = LifecycleId(10L)
  private val epoch  = Epoch.initial

  private def candidate(
    id: String,
    sticky: Boolean = false,
    linkParentOnCrash: Boolean = true,
    view: LiveView[Nothing, Unit] = FirstView,
    metadata: String = "initial"
  ): NestedRegistrationCandidate =
    NestedRegistrationCandidate(
      NestedLifecycleRequirement(
        id,
        sticky,
        linkParentOnCrash,
        NestedLifecycleFactory(() => view)
      ),
      NestedTopic(s"topic-$metadata")
    )

  private def prepare(
    state: NestedTopologyState,
    candidates: Vector[NestedRegistrationCandidate],
    lifecycle: LifecycleId = parent,
    lifecycleEpoch: Epoch = epoch,
    revision: TurnRevision = TurnRevision(1L)
  ): NestedTopologyPlan =
    state.prepare(lifecycle, lifecycleEpoch, revision, candidates).toOption.get

  override def spec = suite("NestedTopologyStateSpec")(
    test("retains exact identity and child while updating mutable policies") {
      val firstPlan = prepare(NestedTopologyState.empty, Vector(candidate("chat", sticky = true)))
      val first = firstPlan.candidateRegistrations.head
      val child = LifecycleId(20L)
      val attached = firstPlan.state
        .attach(first.id, first.epoch, parent, epoch, child, Epoch(3L))
        .toOption
        .get
      val secondCandidate = candidate(
        "chat",
        sticky = true,
        linkParentOnCrash = false,
        view = SecondView,
        metadata = "ignored"
      )
      val secondPlan = prepare(attached, Vector(secondCandidate), revision = TurnRevision(2L))
      val retained = secondPlan.candidateRegistrations.head

      assertTrue(
        retained.id == first.id,
        retained.epoch == first.epoch,
        retained.parentRevision == TurnRevision(2L),
        !retained.linkParentOnCrash,
        retained.factory eq secondCandidate.requirement.factory,
        retained.topic == first.topic,
        secondPlan.state.attachedChild(first.id).contains(AttachedNestedLifecycle(child, Epoch(3L))),
        secondPlan.revokedRegistrationIds.isEmpty,
        secondPlan.childLifecycleIdsToRetire.isEmpty
      )
    },
    test("removal and reintroduction allocate a new id and advance remembered epoch") {
      val firstPlan = prepare(NestedTopologyState.empty, Vector(candidate("chat")))
      val first = firstPlan.candidateRegistrations.head
      val removal = prepare(firstPlan.state, Vector.empty)
      val reintroduced = prepare(removal.state, Vector(candidate("chat"))).candidateRegistrations.head

      assertTrue(
        removal.revokedRegistrationIds == Vector(first.id),
        removal.state.validate(first.id, first.epoch, parent, epoch).isEmpty,
        reintroduced.id != first.id,
        reintroduced.epoch.value == first.epoch.value + 1L
      )
    },
    test("sticky or parent epoch changes replace and revoke a registration") {
      val firstPlan = prepare(NestedTopologyState.empty, Vector(candidate("child", sticky = false)))
      val first = firstPlan.candidateRegistrations.head
      val stickyReplacement = prepare(firstPlan.state, Vector(candidate("child", sticky = true)))
      val second = stickyReplacement.candidateRegistrations.head
      val parentReplacement = prepare(
        stickyReplacement.state,
        Vector(candidate("child", sticky = true)),
        lifecycleEpoch = Epoch(2L)
      )
      val third = parentReplacement.candidateRegistrations.head

      assertTrue(
        stickyReplacement.revokedRegistrationIds == Vector(first.id),
        second.id != first.id,
        second.epoch.value == 2L,
        parentReplacement.revokedRegistrationIds == Vector(second.id),
        third.id != second.id,
        third.epoch.value == 3L
      )
    },
    test("rejects empty and duplicate application ids before allocating a plan") {
      val empty = NestedTopologyState.empty.prepare(
        parent,
        epoch,
        TurnRevision(1L),
        Vector(candidate("  "))
      )
      val duplicate = NestedTopologyState.empty.prepare(
        parent,
        epoch,
        TurnRevision(1L),
        Vector(candidate("same"), candidate("same"))
      )

      assertTrue(
        empty == Left(NestedTopologyError.InvalidApplicationId("  ")),
        duplicate == Left(NestedTopologyError.DuplicateApplicationId("same"))
      )
    },
    test("revokes an attached subtree and returns every lifecycle to retire") {
      val rootPlan = prepare(NestedTopologyState.empty, Vector(candidate("root")))
      val rootRegistration = rootPlan.candidateRegistrations.head
      val childLifecycle = LifecycleId(20L)
      val withRootChild = rootPlan.state
        .attach(rootRegistration.id, rootRegistration.epoch, parent, epoch, childLifecycle, epoch)
        .toOption
        .get

      val childPlan = prepare(withRootChild, Vector(candidate("child")), childLifecycle)
      val childRegistration = childPlan.candidateRegistrations.head
      val grandchildLifecycle = LifecycleId(30L)
      val completeTree = childPlan.state
        .attach(
          childRegistration.id,
          childRegistration.epoch,
          childLifecycle,
          epoch,
          grandchildLifecycle,
          epoch
        )
        .toOption
        .get
      val grandchildPlan = prepare(completeTree, Vector(candidate("grandchild")), grandchildLifecycle)
      val grandchildRegistration = grandchildPlan.candidateRegistrations.head
      val removal = prepare(grandchildPlan.state, Vector.empty)

      assertTrue(
        removal.revokedRegistrationIds ==
          Vector(rootRegistration.id, childRegistration.id, grandchildRegistration.id),
        removal.childLifecycleIdsToRetire == Vector(childLifecycle, grandchildLifecycle),
        removal.state.registrations.isEmpty
      )
    },
    test("validates registration, registration epoch, and parent identity exactly") {
      val plan = prepare(NestedTopologyState.empty, Vector(candidate("exact")))
      val registration = plan.candidateRegistrations.head

      assertTrue(
        plan.state.validate(registration.id, registration.epoch, parent, epoch).contains(registration),
        plan.state.validate(NestedRegistrationId(999L), registration.epoch, parent, epoch).isEmpty,
        plan.state
          .validate(registration.id, NestedRegistrationEpoch(999L), parent, epoch)
          .isEmpty,
        plan.state
          .validate(registration.id, registration.epoch, LifecycleId(999L), epoch)
          .isEmpty,
        plan.state.validate(registration.id, registration.epoch, parent, Epoch(999L)).isEmpty
      )
    },
    test("attaches once only for exact active registration coordinates") {
      val plan = prepare(NestedTopologyState.empty, Vector(candidate("attach")))
      val registration = plan.candidateRegistrations.head
      val child = LifecycleId(20L)
      val stale = plan.state.attach(
        registration.id,
        NestedRegistrationEpoch(999L),
        parent,
        epoch,
        child,
        epoch
      )
      val attached = plan.state
        .attach(registration.id, registration.epoch, parent, epoch, child, epoch)
        .toOption
        .get
      val duplicate = attached.attach(
        registration.id,
        registration.epoch,
        parent,
        epoch,
        LifecycleId(21L),
        epoch
      )

      assertTrue(
        stale == Left(NestedAttachmentError.RegistrationUnavailable(registration.id)),
        attached.attachedChild(registration.id).contains(AttachedNestedLifecycle(child, epoch)),
        duplicate == Left(NestedAttachmentError.RegistrationAlreadyAttached(registration.id)),
        plan.state.attachedChild(registration.id).isEmpty
      )
    },
    test("detaches only the exact child and leaves its registration active") {
      val plan         = prepare(NestedTopologyState.empty, Vector(candidate("detach")))
      val registration = plan.candidateRegistrations.head
      val child        = LifecycleId(20L)
      val childEpoch   = Epoch(3L)
      val attached = plan.state
        .attach(registration.id, registration.epoch, parent, epoch, child, childEpoch)
        .toOption
        .get
      val mismatched = attached.detach(registration.id, child, Epoch(4L))
      val detached   = attached.detach(registration.id, child, childEpoch).toOption.get

      assertTrue(
        mismatched == Left(NestedAttachmentError.AttachedLifecycleMismatch(registration.id)),
        detached.attachedChild(registration.id).isEmpty,
        detached.registration(registration.id).contains(registration)
      )
    },
    test("navigation retains only attached sticky child subtrees") {
      val directPlan = prepare(
        NestedTopologyState.empty,
        Vector(
          candidate("sticky", sticky = true),
          candidate("ordinary"),
          candidate("unjoined-sticky", sticky = true)
        )
      )
      val stickyRegistration = directPlan.candidateRegistrations(0)
      val ordinaryRegistration = directPlan.candidateRegistrations(1)
      val unjoinedRegistration = directPlan.candidateRegistrations(2)
      val stickyChild = AttachedNestedLifecycle(LifecycleId(60L), Epoch(2L))
      val ordinaryChild = AttachedNestedLifecycle(LifecycleId(61L), Epoch(3L))
      val attached = directPlan.state
        .attach(
          stickyRegistration.id,
          stickyRegistration.epoch,
          parent,
          epoch,
          stickyChild.lifecycle,
          stickyChild.epoch
        ).toOption.get
        .attach(
          ordinaryRegistration.id,
          ordinaryRegistration.epoch,
          parent,
          epoch,
          ordinaryChild.lifecycle,
          ordinaryChild.epoch
        ).toOption.get
      val stickyDescendantPlan = prepare(
        attached,
        Vector(candidate("sticky-descendant")),
        stickyChild.lifecycle,
        stickyChild.epoch
      )
      val stickyDescendant = stickyDescendantPlan.candidateRegistrations.head
      val ordinaryDescendantPlan = prepare(
        stickyDescendantPlan.state,
        Vector(candidate("ordinary-descendant")),
        ordinaryChild.lifecycle,
        ordinaryChild.epoch
      )
      val ordinaryDescendant = ordinaryDescendantPlan.candidateRegistrations.head
      val navigation = ordinaryDescendantPlan.state.detachParentForNavigation(parent, epoch)

      assertTrue(
        navigation.detachedStickyChildren ==
          Vector(DetachedStickyNestedLifecycle(stickyRegistration, stickyChild)),
        navigation.revokedRegistrationIds == Vector(
          stickyRegistration.id,
          unjoinedRegistration.id,
          ordinaryRegistration.id,
          ordinaryDescendant.id
        ),
        navigation.childLifecycleIdsToRetire == Vector(ordinaryChild.lifecycle),
        navigation.state.registrations == Vector(stickyDescendant),
        navigation.state.registration(stickyRegistration.id).isEmpty
      )
    },
    test("sticky child leave detaches its registration while preserving descendants") {
      val directPlan = prepare(
        NestedTopologyState.empty,
        Vector(candidate("sticky", sticky = true))
      )
      val registration = directPlan.candidateRegistrations.head
      val child = AttachedNestedLifecycle(LifecycleId(70L), Epoch(4L))
      val attached = directPlan.state
        .attach(
          registration.id,
          registration.epoch,
          parent,
          epoch,
          child.lifecycle,
          child.epoch
        ).toOption.get
      val descendantPlan = prepare(
        attached,
        Vector(candidate("descendant")),
        child.lifecycle,
        child.epoch
      )
      val descendant = descendantPlan.candidateRegistrations.head
      val navigation = descendantPlan.state
        .detachStickyForNavigation(registration.id, child.lifecycle, child.epoch)

      assertTrue(
        navigation.exists(_.detachedStickyChildren ==
          Vector(DetachedStickyNestedLifecycle(registration, child))),
        navigation.exists(_.revokedRegistrationIds == Vector(registration.id)),
        navigation.exists(_.childLifecycleIdsToRetire.isEmpty),
        navigation.exists(_.state.registrations == Vector(descendant))
      )
    },
    test("navigation preserves a sticky lifecycle below an ordinary child") {
      val rootPlan = prepare(NestedTopologyState.empty, Vector(candidate("ordinary")))
      val rootRegistration = rootPlan.candidateRegistrations.head
      val ordinaryChild = AttachedNestedLifecycle(LifecycleId(80L), Epoch(2L))
      val attachedRoot = rootPlan.state
        .attach(
          rootRegistration.id,
          rootRegistration.epoch,
          parent,
          epoch,
          ordinaryChild.lifecycle,
          ordinaryChild.epoch
        ).toOption.get
      val stickyPlan = prepare(
        attachedRoot,
        Vector(candidate("sticky-descendant", sticky = true)),
        ordinaryChild.lifecycle,
        ordinaryChild.epoch
      )
      val stickyRegistration = stickyPlan.candidateRegistrations.head
      val stickyChild = AttachedNestedLifecycle(LifecycleId(81L), Epoch(3L))
      val complete = stickyPlan.state
        .attach(
          stickyRegistration.id,
          stickyRegistration.epoch,
          ordinaryChild.lifecycle,
          ordinaryChild.epoch,
          stickyChild.lifecycle,
          stickyChild.epoch
        ).toOption.get
      val navigation = complete.detachParentForNavigation(parent, epoch)

      assertTrue(
        navigation.detachedStickyChildren ==
          Vector(DetachedStickyNestedLifecycle(stickyRegistration, stickyChild)),
        navigation.childLifecycleIdsToRetire == Vector(ordinaryChild.lifecycle),
        navigation.revokedRegistrationIds ==
          Vector(rootRegistration.id, stickyRegistration.id),
        navigation.state.registrations.isEmpty
      )
    },
    test("activation preserves an attachment arriving on a retained registration") {
      val initialPlan = prepare(NestedTopologyState.empty, Vector(candidate("retained", sticky = true)))
      val initial = initialPlan.candidateRegistrations.head
      val pending = prepare(
        initialPlan.state,
        Vector(candidate("retained", sticky = true, view = SecondView)),
        revision = TurnRevision(2L)
      )
      val child = AttachedNestedLifecycle(LifecycleId(40L), Epoch(4L))
      val attached = initialPlan.state
        .attach(
          initial.id,
          initial.epoch,
          parent,
          epoch,
          child.lifecycle,
          child.epoch
        )
        .toOption
        .get
      val activated = attached.activate(pending).toOption.get

      assertTrue(
        pending.state.attachedChild(initial.id).isEmpty,
        activated.state.attachedChild(initial.id).contains(child),
        activated.revokedRegistrationIds.isEmpty,
        activated.childLifecycleIdsToRetire.isEmpty
      )
    },
    test("activation retires a child attached to a registration after preparation") {
      val initialPlan = prepare(NestedTopologyState.empty, Vector(candidate("removed")))
      val initial = initialPlan.candidateRegistrations.head
      val pending = prepare(initialPlan.state, Vector.empty)
      val child = AttachedNestedLifecycle(LifecycleId(41L), Epoch(5L))
      val attached = initialPlan.state
        .attach(
          initial.id,
          initial.epoch,
          parent,
          epoch,
          child.lifecycle,
          child.epoch
        )
        .toOption
        .get
      val lateDescendantPlan = prepare(
        attached,
        Vector(candidate("late-descendant")),
        lifecycle = child.lifecycle,
        lifecycleEpoch = child.epoch
      )
      val lateDescendant = lateDescendantPlan.candidateRegistrations.head
      val activated = lateDescendantPlan.state.activate(pending).toOption.get

      assertTrue(
        pending.childLifecycleIdsToRetire.isEmpty,
        activated.revokedRegistrationIds == Vector(initial.id, lateDescendant.id),
        activated.childLifecycleIdsToRetire == Vector(child.lifecycle),
        activated.state.registrations.isEmpty
      )
    },
    test("activation preserves unrelated topology added after preparation") {
      val initialPlan = prepare(NestedTopologyState.empty, Vector(candidate("parent-child")))
      val pending = prepare(
        initialPlan.state,
        Vector(candidate("parent-child")),
        revision = TurnRevision(2L)
      )
      val unrelatedParent = LifecycleId(50L)
      val unrelatedPlan = prepare(
        initialPlan.state,
        Vector(candidate("unrelated")),
        lifecycle = unrelatedParent
      )
      val unrelated = unrelatedPlan.candidateRegistrations.head
      val unrelatedChild = AttachedNestedLifecycle(LifecycleId(51L), Epoch(2L))
      val current = unrelatedPlan.state
        .attach(
          unrelated.id,
          unrelated.epoch,
          unrelatedParent,
          epoch,
          unrelatedChild.lifecycle,
          unrelatedChild.epoch
        )
        .toOption
        .get
      val activated = current.activate(pending).toOption.get

      assertTrue(
        activated.state.registration(unrelated.id).contains(unrelated),
        activated.state.attachedChild(unrelated.id).contains(unrelatedChild),
        activated.state.registrations.size == 2
      )
    },
    test("activation rejects a competing plan after the direct registration set changes") {
      val initialPlan = prepare(NestedTopologyState.empty, Vector(candidate("child", sticky = false)))
      val initial = initialPlan.candidateRegistrations.head
      val first = prepare(initialPlan.state, Vector(candidate("child", sticky = true)))
      val competing = prepare(initialPlan.state, Vector.empty)
      val activated = initialPlan.state.activate(first).toOption.get
      val stale = activated.state.activate(competing)

      assertTrue(
        stale == Left(
          NestedTopologyActivationError.StalePlan(
            parent,
            epoch,
            Vector(initial.id),
            first.candidateRegistrations.map(_.id)
          )
        )
      )
    }
  )
