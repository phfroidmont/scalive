package scalive

import zio.*
import zio.test.*

object TopicDispatcherSpec extends ZIOSpecDefault:

  override def spec = suite("TopicDispatcherSpec")(
    test("runs topics concurrently and preserves FIFO within each topic") {
      ZIO.scoped {
        for
          a1Started <- Promise.make[Nothing, Unit]
          releaseA1 <- Promise.make[Nothing, Unit]
          bFinished <- Promise.make[Nothing, Unit]
          a2Finished <- Promise.make[Nothing, Unit]
          aOrder     <- Ref.make(Vector.empty[Int])
          dispatcher <- TopicDispatcher.make[Any, Int] {
                          case ("A", 1) =>
                            aOrder.update(_ :+ 1) *>
                              a1Started.succeed(()).unit *>
                              releaseA1.await
                          case ("A", 2) =>
                            aOrder.update(_ :+ 2) *> a2Finished.succeed(()).unit
                          case ("B", _) => bFinished.succeed(()).unit
                          case _        => ZIO.unit
                        }
          _          <- dispatcher.submit("A", 1)
          _          <- a1Started.await
          _          <- dispatcher.submit("A", 2)
          _          <- dispatcher.submit("B", 1)
          bCompleted <- Live.live(bFinished.await.timeout(1.second))
          _          <- releaseA1.succeed(())
          a2Completed <- Live.live(a2Finished.await.timeout(1.second))
          order       <- aOrder.get
        yield assertTrue(
          bCompleted.contains(()),
          a2Completed.contains(()),
          order == Vector(1, 2)
        )
      }
    },
    test("barrier messages finish before later topics start") {
      ZIO.scoped {
        for
          barrierStarted   <- Promise.make[Nothing, Unit]
          releaseBarrier   <- Promise.make[Nothing, Unit]
          barrierCompleted <- Ref.make(false)
          laterObserved    <- Ref.make(Vector.empty[Boolean])
          laterFinished    <- Promise.make[Nothing, Unit]
          dispatcher <- TopicDispatcher.make[Any, Int] {
                          case ("root", _) =>
                            barrierStarted.succeed(()).unit *>
                              releaseBarrier.await *>
                              barrierCompleted.set(true)
                          case (_, _) =>
                            barrierCompleted.get.flatMap(completed =>
                              laterObserved.modify { observed =>
                                val updated = observed :+ completed
                                (updated.size == 2) -> updated
                              }.flatMap { finished =>
                                if finished then laterFinished.succeed(()).unit else ZIO.unit
                              }
                            )
                        }
          _ <- dispatcher.submitBarrier("root", 1)
          _ <- barrierStarted.await
          _ <- dispatcher.submit("child-a", 1)
          _ <- dispatcher.submit("child-b", 1)
          beforeRelease <- Live.live(laterFinished.await.timeout(100.millis))
          _ <- releaseBarrier.succeed(())
          afterRelease <- Live.live(laterFinished.await.timeout(1.second))
          observed <- laterObserved.get
        yield assertTrue(
          beforeRelease.isEmpty,
          afterRelease.contains(()),
          observed == Vector(true, true)
        )
      }
    },
    test("publishes handler failures") {
      val boom = new RuntimeException("boom")

      ZIO.scoped {
        for
          dispatcher <- TopicDispatcher.make[Any, Int]((_, _) => ZIO.fail(boom))
          _          <- dispatcher.submit("A", 1)
          failure    <- Live.live(dispatcher.failure.flip.timeout(1.second))
        yield assertTrue(failure.contains(boom))
      }
    },
    test("suppresses later topics after a barrier fails") {
      val boom = new RuntimeException("boom")

      ZIO.scoped {
        for
          barrierStarted <- Promise.make[Nothing, Unit]
          releaseBarrier <- Promise.make[Nothing, Unit]
          childRan       <- Promise.make[Nothing, Unit]
          dispatcher <- TopicDispatcher.make[Any, Int] {
                          case ("root", _) =>
                            barrierStarted.succeed(()).unit *>
                              releaseBarrier.await *>
                              ZIO.fail(boom)
                          case _ => childRan.succeed(()).unit
                        }
          _       <- dispatcher.submitBarrier("root", 1)
          _       <- barrierStarted.await
          _       <- dispatcher.submit("child", 1)
          _       <- releaseBarrier.succeed(())
          failure <- dispatcher.failure.flip
          childCompleted <- Live.live(childRan.await.timeout(100.millis))
        yield assertTrue(failure eq boom, childCompleted.isEmpty)
      }
    }
  )
end TopicDispatcherSpec
