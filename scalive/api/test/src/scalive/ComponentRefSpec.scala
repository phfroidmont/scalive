package scalive

import zio.test.*

object ComponentRefSpec extends ZIOSpecDefault:
  def spec = suite("ComponentRefSpec")(
    test("component references expose no constructor or Phoenix CID") {
      val constructorErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val ref = ComponentRef[Int](1)
      """)
      val cidErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def cid(ref: ComponentRef[Int]) = ref.cid
      """)

      assertTrue(constructorErrors.nonEmpty, cidErrors.nonEmpty)
    },
    test("component references remain typed semantic event targets") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def binding(ref: ComponentRef[Int]) = on.click.to(ref)(1)
        def target(ref: ComponentRef[Int]) = phx.target(ref)
      """)

      assertTrue(errors.isEmpty)
    },
    test("component targets reject another message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def target(ref: ComponentRef[String]): Mod.Attr[Int] = phx.target(ref)
      """)

      assertTrue(errors.nonEmpty)
    }
  )
