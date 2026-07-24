package scaliveapi

import zio.test.*

object ComponentApiSpec extends ZIOSpecDefault:

  override def spec = suite("ComponentApiSpec")(
    test("low-level rendered component construction is not a package helper") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val content = component(1, div("content"))
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end ComponentApiSpec
