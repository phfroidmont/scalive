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
          _       <- view.changeForm(Profile.Definition)(Profile.Name.initial(""))
          changed <- view.html
          _       <- view.submitForm(Profile.Definition)(Profile.Email.initial("invalid"))
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
          _ <- view.submitForm(Profile.Definition)(
                 Profile.Name.initial("Ada"),
                 Profile.Email.initial("ada@example.com")
               )
          status <- view.text("[role=status]")
        yield assertTrue(status == "Saved Ada.")
      }
    }
  )
// docs:end forms-test
