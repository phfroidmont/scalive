package scalive.runtime.contracts

import zio.ZIO
import zio.test.*

object RuntimeIdentitiesSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeIdentitiesSpec")(
    test("allocates positive monotonic identities") {
      for
        first  <- ZIO.fromEither(TurnId.fresh())
        second <- ZIO.fromEither(TurnId.fresh())
      yield assertTrue(first.value > 0L, second.value > first.value)
    },
    test("allocates collision-free identities concurrently") {
      for
        identities <- ZIO.foreachPar(1 to 1000)(_ => ZIO.fromEither(CommandId.fresh()))
        values = identities.map(_.value)
      yield assertTrue(values.forall(_ > 0L), values.distinct.size == values.size)
    },
    test("advances epochs without a global allocator") {
      val next = Epoch.next(Epoch.initial)
      assertTrue(Epoch.initial.value == 1L, next.map(_.value) == Right(2L))
    },
    test("reports typed epoch exhaustion") {
      assertTrue(
        Epoch.next(Epoch(Long.MaxValue)) == Left(RuntimeIdentityError.Exhausted("epoch"))
      )
    },
    test("keeps all runtime identity types distinct") {
      for
        lifecycle <- ZIO.fromEither(LifecycleId.fresh())
        command   <- ZIO.fromEither(CommandId.fresh())
        turn      <- ZIO.fromEither(TurnId.fresh())
        revision  <- ZIO.fromEither(TurnRevision.fresh())
      yield assertTrue(
        lifecycle.value > 0L,
        command.value > 0L,
        turn.value > 0L,
        revision.value > 0L
      )
    }
  )
