package scaliveapi

import zio.test.*

object StreamOpacitySpec extends ZIOSpecDefault:
  override def spec = suite("StreamOpacitySpec")(
    test("LiveStream exposes no runtime state to application code") {
      val nameErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def streamName(stream: LiveStream[Int]) = stream.name
      """)
      val entriesErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def entries(stream: LiveStream[Int]) = stream.entries
      """)
      val emptyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def empty(stream: LiveStream[Int]) = stream.isEmpty
      """)
      val nonEmptyErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def nonEmpty(stream: LiveStream[Int]) = stream.nonEmpty
      """)
      val entryTypeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        type Entry = LiveStreamEntry[Int]
      """)
      val extractorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def inspect(stream: LiveStream[Int]) = stream match
          case LiveStream(_, _, _, _, _, _, _) => ()
      """)

      assertTrue(
        nameErrors.nonEmpty,
        entriesErrors.nonEmpty,
        emptyErrors.nonEmpty,
        nonEmptyErrors.nonEmpty,
        entryTypeErrors.nonEmpty,
        extractorErrors.nonEmpty
      )
    }
  )
end StreamOpacitySpec
