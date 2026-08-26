package scalive

import zio.ZIO
import zio.test.*

object NavigationSecuritySpec extends ZIOSpecDefault:
  private val invalidDestination = "/%09/example.com"

  override def spec = suite("NavigationSecuritySpec")(
    test("raw link and JS live-navigation builders reject unsafe destinations") {
      val builders = Vector[() => Any](
        () => link.pushNavigateUnsafe(invalidDestination),
        () => link.replaceNavigateUnsafe(invalidDestination),
        () => link.pushPatchUnsafe(invalidDestination),
        () => link.replacePatchUnsafe(invalidDestination),
        () => JS.pushNavigateUnsafe(invalidDestination),
        () => JS.replaceNavigateUnsafe(invalidDestination),
        () => JS.pushPatchUnsafe(invalidDestination),
        () => JS.replacePatchUnsafe(invalidDestination)
      )

      ZIO.foreach(builders)(builder => ZIO.attempt(builder()).either).map(results =>
        assertTrue(results.forall(_.isLeft))
      )
    }
  )
end NavigationSecuritySpec
