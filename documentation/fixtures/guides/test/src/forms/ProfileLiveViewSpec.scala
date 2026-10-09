// docs:start forms-test
package forms

import zio.*
import zio.test.*

import scalive.testing.*

object ProfileLiveViewSpec extends ZIOSpecDefault:
  private val profiles = new Profiles:
    def save(profile: Profile): Task[Unit] = ZIO.unit

  def spec = suite("ProfileLiveView")(
    test("shows errors for used fields, then every error after submit") {
      ZIO.scoped {
        for
          view <- ConnectedRender.join(ProfileLiveView(profiles))
          _    <- view.changeForm(
                    "#profile-form",
                    Vector(
                      Profile.Name.name        -> "",
                      "profile[_unused_email]" -> "",
                      Profile.Email.name       -> ""
                    ),
                    target = Some(Profile.Name.name)
                  )
          changed <- view.html
          _       <- view.submitForm(
                       "#profile-form",
                       Vector(Profile.Name.name -> "", Profile.Email.name -> "invalid")
                     )
          submitted <- view.html
        yield assertTrue(
          changed.contains("Name is required."),
          !changed.contains("Enter a valid email address."),
          submitted.contains("Enter a valid email address.")
        )
      }
    },
    test("saves a valid profile") {
      ZIO.scoped {
        for
          view <- ConnectedRender.join(ProfileLiveView(profiles))
          _    <- view.submitForm(
                    "#profile-form",
                    Vector(Profile.Name.name -> "Ada", Profile.Email.name -> "ada@example.com")
                  )
          status <- view.text("[role=status]")
        yield assertTrue(status == "Saved Ada.")
      }
    }
  )
// docs:end forms-test
