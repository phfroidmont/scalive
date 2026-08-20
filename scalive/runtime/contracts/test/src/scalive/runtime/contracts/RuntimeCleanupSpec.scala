package scalive.runtime.contracts

import zio.*
import zio.test.*

object RuntimeCleanupSpec extends ZIOSpecDefault:
  override def spec = suite("RuntimeCleanupSpec")(
    test("attempts every cleanup in order and aggregates defects") {
      for
        attempted <- Ref.make(Vector.empty[Int])
        result <- RuntimeCleanup
                    .all(
                      Vector(
                        attempted.update(_ :+ 1) *> ZIO.dieMessage("first cleanup failed"),
                        attempted.update(_ :+ 2),
                        attempted.update(_ :+ 3) *> ZIO.dieMessage("third cleanup failed")
                      )
                    ).exit
        values <- attempted.get
        details = result.causeOption.map(_.prettyPrint).getOrElse("")
      yield assertTrue(
        values == Vector(1, 2, 3),
        details.contains("first cleanup failed"),
        details.contains("third cleanup failed")
      )
    },
    test("accepts an empty cleanup collection") {
      RuntimeCleanup.all(Vector.empty).exit.map(result => assertTrue(result.isSuccess))
    },
    test("defers interruption until every cleanup has run") {
      for
        attempted <- Ref.make(Vector.empty[Int])
        started   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        cleanup <- RuntimeCleanup
                     .all(
                       Vector(
                         attempted.update(_ :+ 1) *> started.succeed(()).unit *> release.await,
                         attempted.update(_ :+ 2)
                       )
                     ).fork
        _            <- started.await
        interruption <- cleanup.interrupt.fork
        _            <- interruption.status.repeatUntil(_.isSuspended)
        beforeRelease <- interruption.poll
        _            <- release.succeed(())
        _            <- interruption.join
        values       <- attempted.get
      yield assertTrue(beforeRelease.isEmpty, values == Vector(1, 2))
    }
  )
