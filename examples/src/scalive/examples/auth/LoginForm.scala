package scalive.examples.auth

import scalive.*

final case class LoginCredentials(email: String, password: String)

object LoginForm:
  val FormId = "login-form"
  val Root   = FormPath("login")

  val EmailMaxLength    = 254
  val PasswordMaxLength = 1024

  val Email = FormField
    .requiredString(Root / "email")
    .validate(s"must be $EmailMaxLength characters or fewer")(_.length <= EmailMaxLength)

  val Password = FormField
    .requiredString(Root / "password")
    .validate(s"must be $PasswordMaxLength characters or fewer")(
      _.length <= PasswordMaxLength
    )

  val codec: FormCodec[LoginCredentials] =
    Email.codec.zip(Password.codec).map(LoginCredentials.apply)
