package scalive.runtime.connection

import zio.*
import zio.test.*

object SerialWriterSpec extends ZIOSpecDefault:
  override def spec = suite("SerialWriterSpec")(
    test("writes in FIFO order and close is idempotent") {
      ZIO.scoped {
        for
          values <- Ref.make(Vector.empty[Int])
          writer <- SerialWriter.make[Int](2)(value => values.update(_ :+ value))
          _      <- writer.send(1) *> writer.send(2) *> writer.send(3)
          _      <- writer.close *> writer.close
          actual <- values.get
          closed <- writer.send(4).either
        yield assertTrue(actual == Vector(1, 2, 3), closed == Left(SerialWriter.Error.Shutdown))
      }
    },
    test("a failed write is terminal for pending and future sends") {
      ZIO.scoped {
        val boom = RuntimeException("boom")
        for
          writer <- SerialWriter.make[Int](2)(_ => ZIO.fail(boom))
          first  <- writer.send(1).either
          future <- writer.send(2).either
        yield assertTrue(
          first == Left(SerialWriter.Error.WriteFailed(boom)),
          future == Left(SerialWriter.Error.WriteFailed(boom))
        )
      }
    },
    test("a synchronously thrown write is terminal") {
      ZIO.scoped {
        val boom = RuntimeException("synchronous boom")
        for
          writer <- SerialWriter.make[Int](1)(_ => throw boom)
          first  <- writer.send(1).either
          future <- writer.send(2).either
        yield assertTrue(
          first == Left(SerialWriter.Error.WriteFailed(boom)),
          future == Left(SerialWriter.Error.WriteFailed(boom))
        )
      }
    },
    test("bounds pending writes and never overlaps sink calls") {
      ZIO.scoped {
        for
          release <- Promise.make[Nothing, Unit]
          entered <- Queue.unbounded[Int]
          active  <- Ref.make(0)
          maximum <- Ref.make(0)
          writer <- SerialWriter.make[Int](1) { value =>
                      active.updateAndGet(_ + 1).flatMap(now =>
                        maximum.update(_.max(now)) *> entered.offer(value) *> release.await
                          .ensuring(active.update(_ - 1))
                      )
                    }
           first <- writer.send(1).fork
           _     <- entered.take
           _      <- writer.offer(2)
           saturated <- writer.send(3).either
           _         <- release.succeed(())
           _         <- first.join
           max       <- maximum.get
        yield assertTrue(saturated == Left(SerialWriter.Error.Saturated(1)), max == 1)
      }
    },
    test("close and immediate scope shutdown complete an in-flight blocked send") {
      for
        scope   <- Scope.make
        entered <- Promise.make[Nothing, Unit]
        blocked <- Promise.make[Nothing, Unit]
        writer <- scope.extend(
                    SerialWriter.make[Int](1)(_ => entered.succeed(()).unit *> blocked.await)
                  )
        sending <- writer.send(1).either.fork
        _       <- entered.await
        _       <- writer.close *> scope.close(Exit.unit)
        result  <- sending.join
      yield assertTrue(result == Left(SerialWriter.Error.Shutdown))
    },
    test("offers bounded output without waiting for the sink") {
      ZIO.scoped {
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          writer  <- SerialWriter.make[Int](1)(_ => entered.succeed(()).unit *> release.await)
          offered <- writer.offer(1).timeout(1.second)
          _       <- entered.await
          _       <- release.succeed(())
        yield assertTrue(offered.contains(()))
      }
    }
  )
