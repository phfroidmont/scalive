// docs:start learn-signup-test
package quickstart

import zio.*
import zio.test.*

import scalive.testing.*

object SignupLiveViewSpec extends ZIOSpecDefault:
  def spec = suite("SignupLiveView")(
    test("shows every error when an invalid form is submitted") {
      ZIO.scoped {
        for
          view <- ConnectedRender.join(SignupLiveView())
          _    <- view.submitForm(
                    "#signup-form",
                    Vector(Signup.Name.name -> "", Signup.Email.name -> "ada")
                  )
          html <- view.html
        yield assertTrue(
          html.contains("Enter your name."),
          html.contains("Enter an email address such as ada@example.com.")
        )
      }
    },
    test("thanks the visitor and clears the form after a valid submission") {
      ZIO.scoped {
        for
          view <- ConnectedRender.join(SignupLiveView())
          _    <- view.submitForm(
                    "#signup-form",
                    Vector(Signup.Name.name -> "Ada", Signup.Email.name -> "ada@example.com")
                  )
          status <- view.text("[role=status]")
          html   <- view.html
          name    = RenderedHtml.parse(html).selectOne("input[type=text]").map(_.value)
        yield assertTrue(status == "Thanks, Ada. Check ada@example.com.", name == Right(""))
      }
    }
  )
// docs:end learn-signup-test
