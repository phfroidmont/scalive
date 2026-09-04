package scalive.docs.auth

import scalive.*

final case class LoginCredentials(email: String, password: String)

object LoginForm:
  val FormId = "authentication-lab-login"
  val Root   = FormRoot("login")

  val EmailMaxLength    = 254
  val PasswordMaxLength = 1024

  val Email = Root
    .text("email")
    .required()
    .validate(FieldIssue(s"must be $EmailMaxLength characters or fewer"))(
      _.length <= EmailMaxLength
    )

  val Password = Root
    .text("password")
    .required()
    .validate(FieldIssue(s"must be $PasswordMaxLength characters or fewer"))(
      _.length <= PasswordMaxLength
    )

  val Definition = Root.product[LoginCredentials]((Email, Password))
