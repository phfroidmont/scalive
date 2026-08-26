package scalive.runtime.connection

import zio.*
import zio.test.*

object BufferedActivationSinkSpec extends ZIOSpecDefault:
  final private case class Overflow(capacity: Int) extends Exception

  override def spec = suite("BufferedActivationSinkSpec")(
    test("buffers in order until activation and forwards later values") {
      for
        observed <- Ref.make(Vector.empty[Int])
        sink     <- BufferedActivationSink.make[Int](2, value => observed.update(_ :+ value), Overflow(_))
        _        <- sink.offer(1) *> sink.offer(2)
        before   <- observed.get
        _        <- sink.activate *> sink.offer(3)
        after    <- observed.get
      yield assertTrue(before.isEmpty, after == Vector(1, 2, 3))
    },
    test("rejects output beyond the pre-activation capacity") {
      for
        sink <- BufferedActivationSink.make[Int](1, _ => ZIO.unit, Overflow(_))
        _    <- sink.offer(1)
        full <- sink.offer(2).either
      yield assertTrue(full == Left(Overflow(1)))
    },
    test("activation observes values offered after its effect is constructed") {
      for
        observed <- Ref.make(Vector.empty[Int])
        sink     <- BufferedActivationSink.make[Int](2, value => observed.update(_ :+ value), Overflow(_))
        _         <- sink.offer(1)
        activation = sink.activate
        _         <- sink.offer(2)
        _         <- activation
        actual    <- observed.get
      yield assertTrue(actual == Vector(1, 2))
    },
    test("an offer waiting behind activation observes the active state") {
      for
        observed         <- Ref.make(Vector.empty[Int])
        activationEntered <- Promise.make[Nothing, Unit]
        releaseActivation <- Promise.make[Nothing, Unit]
        sink <- BufferedActivationSink.make[Int](
                  2,
                  value =>
                    observed.update(_ :+ value) *>
                      ZIO
                        .when(value == 1)(
                          activationEntered.succeed(()).unit *> releaseActivation.await
                        ).unit,
                  Overflow(_)
                )
        _         <- sink.offer(1)
        activation <- sink.activate.fork
        _          <- activationEntered.await
        pending    <- sink.offer(2).fork
        _          <- ZIO.yieldNow
        _          <- releaseActivation.succeed(())
        _          <- activation.join *> pending.join
        actual    <- observed.get
      yield assertTrue(actual == Vector(1, 2))
    }
  )
