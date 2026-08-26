package scaliveapi

import zio.test.*

object LinkApiSpec extends ZIOSpecDefault:
  def spec = suite("LinkApiSpec")(
    test("accepts typed and unsafe signal-backed destinations") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def links[Msg](
          location: Signal[LiveLocation],
          raw: Signal[String],
          content: Mod[Msg]
        ): Vector[HtmlElement[Msg]] = Vector(
          link.pushNavigate(location, content),
          link.replaceNavigate(location, content),
          link.pushPatch(location, content),
          link.replacePatch(location, content),
          link.pushNavigateUnsafe(raw, content),
          link.replaceNavigateUnsafe(raw, content),
          link.pushPatchUnsafe(raw, content),
          link.replacePatchUnsafe(raw, content)
        )
      """)

      assertTrue(errors.isEmpty)
    },
    test("keeps typed and unsafe signal-backed destinations distinct") {
      val pushNavigateAcceptsRaw = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(raw: Signal[String]) = link.pushNavigate(raw)
      """)
      val replaceNavigateAcceptsRaw = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(raw: Signal[String]) = link.replaceNavigate(raw)
      """)
      val pushPatchAcceptsRaw = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(raw: Signal[String]) = link.pushPatch(raw)
      """)
      val replacePatchAcceptsRaw = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(raw: Signal[String]) = link.replacePatch(raw)
      """)
      val pushNavigateUnsafeAcceptsLocation = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(location: Signal[LiveLocation]) = link.pushNavigateUnsafe(location)
      """)
      val replaceNavigateUnsafeAcceptsLocation = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(location: Signal[LiveLocation]) = link.replaceNavigateUnsafe(location)
      """)
      val pushPatchUnsafeAcceptsLocation = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(location: Signal[LiveLocation]) = link.pushPatchUnsafe(location)
      """)
      val replacePatchUnsafeAcceptsLocation = scala.compiletime.testing.typeChecks("""
        import scalive.*
        def invalid(location: Signal[LiveLocation]) = link.replacePatchUnsafe(location)
      """)

      assertTrue(
        !pushNavigateAcceptsRaw,
        !replaceNavigateAcceptsRaw,
        !pushPatchAcceptsRaw,
        !replacePatchAcceptsRaw,
        !pushNavigateUnsafeAcceptsLocation,
        !replaceNavigateUnsafeAcceptsLocation,
        !pushPatchUnsafeAcceptsLocation,
        !replacePatchUnsafeAcceptsLocation
      )
    }
  )
end LinkApiSpec
