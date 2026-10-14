package httpforms

import zio.http.Method

import scalive.*

// docs:start http-forms-definition
final case class Credentials(email: String, password: String)

object LoginForm:
  val Root     = FormRoot("login")
  val Email    = Root.text("email").required(FieldIssue("Enter your email address."))
  val Password = Root.text("password").required(FieldIssue("Enter your password."))

  val Definition = Root.product[Credentials]((email = Email, password = Password))
// docs:end http-forms-definition

// docs:start http-forms-routes
object Pages:
  val login     = live / "login"
  val dashboard = live / "dashboard"

val SessionRoute = Method.POST / "session"
val SignOutRoute = Method.POST / "session" / "sign-out"
// docs:end http-forms-routes

val LoginError = FlashKind("error")
