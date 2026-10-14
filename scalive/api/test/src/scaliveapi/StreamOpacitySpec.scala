package scaliveapi

import zio.test.*

object StreamOpacitySpec extends ZIOSpecDefault:
  override def spec = suite("StreamOpacitySpec")(
    test("stream state is not part of the application API") {
      val handleErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def keep(stream: LiveStream[Int]) = ()
      """)
      val entryTypeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        type Entry = LiveStreamEntry[Int]
      """)
      val operationErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        val Items = LiveStreamDef.byId[Int, Int]("items")(identity)
        def insert(ctx: MessageContext[Unit, Unit]): Task[Int] = ctx.streams.insert(Items, 1)
      """)

      assertTrue(handleErrors.nonEmpty, entryTypeErrors.nonEmpty, operationErrors.nonEmpty)
    }
  )
end StreamOpacitySpec
