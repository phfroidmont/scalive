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
      val safeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def invalid(raw: Signal[String]) = (
          link.pushNavigate(raw),
          link.replaceNavigate(raw),
          link.pushPatch(raw),
          link.replacePatch(raw)
        )
      """)
      val unsafeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def invalid(location: Signal[LiveLocation]) = (
          link.pushNavigateUnsafe(location),
          link.replaceNavigateUnsafe(location),
          link.pushPatchUnsafe(location),
          link.replacePatchUnsafe(location)
        )
      """)

      assertTrue(
        safeErrors.exists(_.lineContent.contains("link.pushNavigate(raw)")),
        safeErrors.exists(_.lineContent.contains("link.replaceNavigate(raw)")),
        safeErrors.exists(_.lineContent.contains("link.pushPatch(raw)")),
        safeErrors.exists(_.lineContent.contains("link.replacePatch(raw)")),
        unsafeErrors.exists(_.lineContent.contains("link.pushNavigateUnsafe(location)")),
        unsafeErrors.exists(_.lineContent.contains("link.replaceNavigateUnsafe(location)")),
        unsafeErrors.exists(_.lineContent.contains("link.pushPatchUnsafe(location)")),
        unsafeErrors.exists(_.lineContent.contains("link.replacePatchUnsafe(location)"))
      )
    }
  )
end LinkApiSpec
