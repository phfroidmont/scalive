package scalive.examples.auth

import scalive.*

final case class LoginCredentials(email: String, password: String)

final case class LoginSubmission(
  csrfToken: LoginCsrfToken,
  credentials: LoginCredentials)

object LoginForm:
  val Root         = FormPath("login")
  val CsrfPath     = Root / "csrf"
  val EmailPath    = Root / "email"
  val PasswordPath = Root / "password"

  val CsrfMaxLength     = 256
  val EmailMaxLength    = 254
  val PasswordMaxLength = 1024

  val codec: FormCodec[LoginSubmission] =
    FormCodec { data =>
      for
        csrf     <- required(data, CsrfPath, CsrfMaxLength)
        email    <- required(data, EmailPath, EmailMaxLength)
        password <- required(data, PasswordPath, PasswordMaxLength)
      yield LoginSubmission(
        LoginCsrfToken(csrf),
        LoginCredentials(email, password)
      )
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
