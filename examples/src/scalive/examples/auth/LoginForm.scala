package scalive.examples.auth

import scalive.*

final case class LoginCredentials(email: String, password: String)

object LoginForm:
  val FormId       = "login-form"
  val Root         = FormPath("login")
  val EmailPath    = Root / "email"
  val PasswordPath = Root / "password"

  val EmailId    = EmailPath.segments.mkString("_")
  val PasswordId = PasswordPath.segments.mkString("_")

  val EmailMaxLength    = 254
  val PasswordMaxLength = 1024

  val codec: FormCodec[LoginCredentials] =
    FormCodec { data =>
      for
        email    <- required(data, EmailPath, EmailMaxLength)
        password <- required(data, PasswordPath, PasswordMaxLength)
      yield LoginCredentials(email, password)
    }

  private def required(
    data: FormData,
    path: FormPath,
    maxLength: Int
  ): Either[FormErrors, String] =
    data.values(path) match
      case Vector(value) if value.nonEmpty && value.length <= maxLength => Right(value)
      case Vector(value) if value.length > maxLength                    =>
        Left(FormErrors.one(path, s"must be $maxLength characters or fewer"))
      case Vector(_) | Vector() => Left(FormErrors.one(path, "can't be blank"))
      case _                    => Left(FormErrors.one(path, "must be submitted exactly once"))
end LoginForm
