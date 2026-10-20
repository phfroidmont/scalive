package scaliveapi

import zio.test.*

import scalive.*

object ContextAppendSpec extends ZIOSpecDefault:
  final private case class User(name: String)
  final private case class Org(id: String)

  private def appended[In, Out, Result](input: In, output: Out)(using
    append: ContextAppend.Aux[In, Out, Result]
  ): (Result, In) =
    val result = append.append(input, output)
    result -> append.left(result)

  override def spec = suite("ContextAppendSpec")(
    test("named contexts accumulate into one named tuple read by name") {
      val (two, first)    = appended((user = User("ada")), (org = Org("acme")))
      val (three, second) = appended(two, (locale = "fr"))

      assertTrue(
        two.user == User("ada"),
        two.org == Org("acme"),
        first.user == User("ada"),
        three.user == User("ada"),
        three.org == Org("acme"),
        three.locale == "fr",
        second == two
      )
    },
    test("named contexts still destructure by position") {
      val (context, _) = appended((user = User("ada")), (org = Org("acme")))
      val describe: ((user: User, org: Org)) => String = (user, org) => s"${user.name}@${org.id}"

      assertTrue(describe(context) == "ada@acme")
    },
    test("unnamed contexts keep accumulating as a flat tuple") {
      val (two, first)    = appended(User("ada"), Org("acme"))
      val (three, second) = appended(two, "fr")

      assertTrue(
        two == (User("ada"), Org("acme")),
        first == User("ada"),
        three == (User("ada"), Org("acme"), "fr"),
        second == two
      )
    },
    test("rejects two named contexts with the same name at compile time") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        summon[ContextAppend[(user: String), (user: Int)]]
      """)

      assertTrue(errors.exists(_.message.contains("same name")))
    }
  )
end ContextAppendSpec
