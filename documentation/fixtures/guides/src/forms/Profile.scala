package forms

import zio.*

import scalive.*

// docs:start forms-definition
final case class Profile(name: String, email: String)

object Profile:
  private val EmailPattern = "[^@\\s]+@[^@\\s]+".r

  val Root = FormRoot("profile")

  val Name = Root
    .text("name")
    .map(_.trim)
    .required(FieldIssue("validation.name.required", Some("required")))

  val Email = Root
    .text("email")
    .map(_.trim)
    .required(FieldIssue("validation.email.required", Some("required")))
    .validate(FieldIssue("validation.email.invalid", Some("invalid_email")))(
      EmailPattern.matches
    )

  val Definition = Root.product[Profile]((name = Name, email = Email))
// docs:end forms-definition

trait Profiles:
  def save(profile: Profile): Task[Unit]
