package scaliverenderapi

import zio.test.*

object AttributeValueSpec extends ZIOSpecDefault:
  override def spec = suite("AttributeValueSpec")(
    test("only rendering makes a composite attribute value, whose static text is never empty") {
      val compositeErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.AttributeValue

        val value = AttributeValue.Composite("", "dynamic")
      """)
      val textErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.AttributeValue

        val value = AttributeValue.Text("text")
      """)

      assertTrue(compositeErrors.nonEmpty, textErrors.isEmpty)
    }
  )
end AttributeValueSpec
