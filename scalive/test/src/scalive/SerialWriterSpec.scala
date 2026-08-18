package scalive

import zio.*
import zio.test.*

object SerialWriterSpec extends ZIOSpecDefault:

  override def spec = suite("SerialWriterSpec")(
    test("serializes concurrent sends in queue order") {
      ZIO.scoped {
        for
          active        <- Ref.make(0)
          maximum       <- Ref.make(0)
          order         <- Ref.make(Vector.empty[Int])
          firstStarted  <- Promise.make[Nothing, Unit]
          firstRelease  <- Promise.make[Nothing, Unit]
          secondStarted <- Promise.make[Nothing, Unit]
          secondRelease <- Promise.make[Nothing, Unit]
          writer        <- SerialWriter.make[Int] { value =>
                      val waitForRelease = value match
                        case 1 => firstStarted.succeed(()).unit *> firstRelease.await
                        case 2 => secondStarted.succeed(()).unit *> secondRelease.await
                        case _ => ZIO.unit

                      (for
                        count <- active.updateAndGet(_ + 1)
                        _     <- maximum.update(_ max count)
                        _     <- order.update(_ :+ value)
                        _     <- waitForRelease
                      yield ()).ensuring(active.update(_ - 1))
                    }
          first  <- writer.send(1).fork
          _      <- firstStarted.await
          second <- writer.send(2).fork
          _      <- ZIO.yieldNow
          _      <- firstRelease.succeed(())
          _      <- secondStarted.await
          third  <- writer.send(3).fork
          _      <- ZIO.yieldNow
          _      <- secondRelease.succeed(())
          _      <- first.join *> second.join *> third.join
          seen   <- order.get
          peak   <- maximum.get
        yield assertTrue(seen == Vector(1, 2, 3), peak == 1)
      }
    },
    test("fails the send and publishes a terminal write failure") {
      val boom = new RuntimeException("boom")

      ZIO.scoped {
        for
          writer        <- SerialWriter.make[Int](_ => ZIO.fail(boom))
          sendFailure   <- writer.send(1).flip
          signalFailure <- writer.failure.flip
        yield assertTrue(sendFailure eq boom, signalFailure eq boom)
      }
    },
    test("fails queued and future sends after a terminal write failure") {
      val boom = new RuntimeException("boom")

      ZIO.scoped {
        for
          firstStarted <- Promise.make[Nothing, Unit]
          releaseFirst <- Promise.make[Nothing, Unit]
          writer <- SerialWriter.make[Int] { value =>
                      if value == 1 then firstStarted.succeed(()).unit *> releaseFirst.await *> ZIO.fail(boom)
                      else ZIO.unit
                    }
          first  <- writer.send(1).fork
          _      <- firstStarted.await
          second <- writer.send(2).fork
          _      <- releaseFirst.succeed(())
          firstFailure  <- first.join.flip
          secondFailure <- second.join.flip
          futureFailure <- writer.send(3).flip
        yield assertTrue(
          firstFailure eq boom,
          secondFailure eq boom,
          futureFailure eq boom
        )
      }
    }
  )
end SerialWriterSpec
