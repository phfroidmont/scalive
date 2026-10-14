// docs:start learn-signup-definition
package quickstart

import scalive.*

final case class Signup(name: String, email: String)

object Signup:
  val Root = FormRoot("signup")

  val Name = Root
    .text("name")
    .map(_.trim)
    .required(FieldIssue("Enter your name."))

  val Email = Root
    .text("email")
    .map(_.trim)
    .required(FieldIssue("Enter your email address."))
    .validate(FieldIssue("Enter an email address such as ada@example.com."))(_.contains("@"))

  val Definition = Root.product[Signup]((name = Name, email = Email))
// docs:end learn-signup-definition
