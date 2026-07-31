package scalive.examples.auth

import scalive.*

final case class LoginCredentials(email: String, password: String)

object LoginForm:
  val FormId = "login-form"
  val Root   = FormRoot("login")

  val EmailMaxLength    = 254
  val PasswordMaxLength = 1024

  val Email = Root
    .requiredString("email")
    .validate(s"must be $EmailMaxLength characters or fewer")(_.length <= EmailMaxLength)

  val Password = Root
    .requiredString("password")
    .validate(s"must be $PasswordMaxLength characters or fewer")(
      _.length <= PasswordMaxLength
    )

  val Definition = Root.form(Email.codec.zip(Password.codec).map(LoginCredentials.apply))
