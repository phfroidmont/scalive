package scalive.runtime.connection

import zio.test.*

object ConnectionConfigSpec extends ZIOSpecDefault:
  override def spec = suite("ConnectionConfigSpec")(
    test("all bounded capacities must be positive") {
      assertTrue(
        ConnectionConfig.make(0, 1, 1, 1, 1) == Left(ConnectionConfig.Error.InvalidIngressCapacity(0)),
        ConnectionConfig.make(1, 0, 1, 1, 1) == Left(
          ConnectionConfig.Error.InvalidOutboundReservationCapacity(0)
        ),
        ConnectionConfig.make(1, 1, 0, 1, 1) == Left(
          ConnectionConfig.Error.InvalidKernelMailboxCapacity(0)
        ),
        ConnectionConfig.make(1, 1, 1, 0, 1) == Left(
          ConnectionConfig.Error.InvalidContinuationCapacity(0)
        ),
        ConnectionConfig.make(1, 1, 1, 1, 0) == Left(
          ConnectionConfig.Error.InvalidWriterCapacity(0)
        ),
        ConnectionConfig.make(1, 2, 3, 4, 5).isRight
      )
    }
  )
