package scalive.runtime.resources

import zio.Promise
import zio.Ref
import zio.ZIO
import zio.test.*

object PreparedResourceSpec extends ZIOSpecDefault:
  override def spec = suite("PreparedResourceSpec")(
    test("starts inactive and keeps workers waiting before commit") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        waiter   <- resource.awaitActivation.fork
        _        <- ZIO.yieldNow
        waiting  <- waiter.poll
        state    <- resource.state
        _        <- resource.close
      yield assertTrue(state == PreparedResource.State.Inactive, waiting.isEmpty)
    },
    test("activation opens the gate") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        waiter   <- resource.awaitActivation.fork
        _        <- resource.activate
        result   <- waiter.join
        state    <- resource.state
        _        <- resource.close
      yield assertTrue(result == (), state == PreparedResource.State.Active)
    },
    test("activation transitions state before waking a worker") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        observed <- (resource.awaitActivation *> resource.state).fork
        _        <- resource.activate
        state    <- observed.join
        _        <- resource.close
      yield assertTrue(state == PreparedResource.State.Active)
    },
    test("activation is idempotent") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        _        <- ZIO.foreachDiscard(1 to 100)(_ => resource.activate)
        result   <- resource.awaitActivation
        state    <- resource.state
        _        <- resource.close
      yield assertTrue(result == (), state == PreparedResource.State.Active)
    },
    test("an active resource is stale while its finalizer runs") {
      for
        finalizerStarted <- Promise.make[Nothing, Unit]
        finishFinalizer  <- Promise.make[Nothing, Unit]
        resource <- PreparedResource.make(finalizerStarted.succeed(()).unit *> finishFinalizer.await)
        _       <- resource.activate
        closing <- resource.close.fork
        _       <- finalizerStarted.await
        stale   <- resource.state
        _       <- finishFinalizer.succeed(())
        _       <- closing.join
        closed  <- resource.state
      yield assertTrue(
        stale == PreparedResource.State.Stale,
        closed == PreparedResource.State.Closed
      )
    },
    test("rollback wakes a waiter with the typed closed failure") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        waiter <- resource.awaitActivation.exit
          .flatMap(exit => resource.state.map(exit -> _))
          .fork
        _        <- resource.close
        observed <- waiter.join
      yield assertTrue(
        observed == (zio.Exit.fail(PreparedResource.Closed), PreparedResource.State.Closed)
      )
    },
    test("stale transition precedes waking a closed worker") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        waiter <- resource.awaitActivation.exit
          .flatMap(exit => resource.state.map(exit -> _))
          .fork
        _        <- resource.markStale
        observed <- waiter.join
        _        <- resource.close
      yield assertTrue(
        observed == (zio.Exit.fail(PreparedResource.Closed), PreparedResource.State.Stale)
      )
    },
    test("concurrent close runs the finalizer exactly once") {
      for
        finalized <- Ref.make(0)
        resource  <- PreparedResource.make(finalized.update(_ + 1))
        _         <- ZIO.foreachParDiscard(1 to 100)(_ => resource.close)
        count     <- finalized.get
      yield assertTrue(count == 1)
    },
    test("collection close runs every finalizer and combines defects") {
      for
        laterRan <- Ref.make(false)
        first    <- PreparedResource.make(ZIO.dieMessage("first finalizer"))
        second <- PreparedResource.make(
          laterRan.set(true) *> ZIO.dieMessage("second finalizer")
        )
        result <- PreparedResources(Vector(first, second)).close.exit
        ran    <- laterRan.get
        defects = result match
          case zio.Exit.Failure(cause) => cause.defects.map(_.getMessage)
          case zio.Exit.Success(_)     => Nil
      yield assertTrue(
        ran,
        defects == List("first finalizer", "second finalizer")
      )
    },
    test("close and activation races resolve to one legal terminal history") {
      ZIO.foreach(1 to 100) { _ =>
        for
          resource <- PreparedResource.make(ZIO.unit)
          waiter   <- resource.awaitActivation.fork
          _        <- resource.activate.zipPar(resource.close)
          result   <- waiter.await
          state    <- resource.state
        yield assertTrue(
          state == PreparedResource.State.Closed,
          result.isSuccess || result == zio.Exit.fail(PreparedResource.Closed)
        )
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("stale and closed resources cannot be reactivated") {
      for
        resource <- PreparedResource.make(ZIO.unit)
        _        <- resource.markStale
        _        <- resource.activate
        stale    <- resource.state
        result   <- resource.awaitActivation.exit
        _        <- resource.close
        _        <- resource.activate
        closed   <- resource.state
      yield assertTrue(
        stale == PreparedResource.State.Stale,
        result == zio.Exit.fail(PreparedResource.Closed),
        closed == PreparedResource.State.Closed
      )
    },
    test("candidate discard closes inactive resources but leaves committed resources owned") {
      for
        inactiveClosed <- Ref.make(0)
        inactive       <- PreparedResource.make(inactiveClosed.update(_ + 1))
        _              <- inactive.discard
        inactiveState  <- inactive.state
        inactiveCount  <- inactiveClosed.get
        activeClosed   <- Ref.make(0)
        active         <- PreparedResource.make(activeClosed.update(_ + 1))
        _              <- active.activate
        _              <- active.discard
        activeState    <- active.state
        retainedCount  <- activeClosed.get
        _              <- active.close
        finalCount     <- activeClosed.get
      yield assertTrue(
        inactiveState == PreparedResource.State.Closed,
        inactiveCount == 1,
        activeState == PreparedResource.State.Active,
        retainedCount == 0,
        finalCount == 1
      )
    },
    test("registry registers ownership before returning and snapshots resources") {
      for
        registered <- Ref.make(Vector.empty[ZIO[Any, Nothing, Unit]])
        registry   <- PreparedResourceRegistry.make(close => registered.update(_ :+ close))
        first      <- registry.prepare(ZIO.unit)
        second     <- registry.prepare(ZIO.unit)
        callbacks  <- registered.get
        result     <- registry.result
        _          <- ZIO.foreachDiscard(callbacks)(identity)
      yield assertTrue(
        callbacks.size == 2,
        result.values == Vector(first, second)
      )
    },
    test("registry preparation cannot be interrupted before ownership is registered") {
      for
        registrationStarted <- Promise.make[Nothing, Unit]
        allowRegistration    <- Promise.make[Nothing, Unit]
        registered           <- Ref.make(Option.empty[ZIO[Any, Nothing, Unit]])
        finalized            <- Ref.make(false)
        registry <- PreparedResourceRegistry.make { close =>
          registrationStarted.succeed(()).unit *>
            allowRegistration.await *>
            registered.set(Some(close))
        }
        preparing   <- registry.prepare(finalized.set(true)).fork
        _           <- registrationStarted.await
        interrupting <- preparing.interrupt.fork
        _           <- allowRegistration.succeed(())
        _           <- interrupting.join
        callback    <- registered.get.someOrFailException
        result      <- registry.result
        _           <- callback
        didFinalize <- finalized.get
      yield assertTrue(result.values.size == 1, didFinalize)
    }
  )
