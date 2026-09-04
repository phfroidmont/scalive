package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.{FormData, FormEventKind}
import scalive.testing.{ConnectedRender, ConnectedView}

object RepeatedContactsFormExampleSpec extends ZIOSpecDefault:
  private def document(view: ConnectedView[?]) =
    view.html.map(Jsoup.parseBodyFragment)

  private def row(key: String, name: String, email: String) = Vector(
    s"contact_book[contacts][$key][_scalive_row]" -> "1",
    s"contact_book[contacts][$key][name]"         -> name,
    s"contact_book[contacts][$key][email]"        -> email
  )

  private val initialFields =
    row("contact-1", "Ada Lovelace", "ada@example.com") ++
      row("contact-2", "Grace Hopper", "grace@example.com")

  private val duplicateNameFields = Vector(
    "contact_book[contacts][contact-1][_scalive_row]" -> "1",
    "contact_book[contacts][contact-1][name]"         -> "Ada Lovelace",
    "contact_book[contacts][contact-1][name]"         -> "Duplicate",
    "contact_book[contacts][contact-1][email]"        -> "ada@example.com"
  ) ++ row("contact-2", "Grace Hopper", "grace@example.com")

  override def spec = suite("RepeatedContactsFormExampleSpec")(
    test("keeps repeated contact identities stable through the complete form workflow") {
      ZIO.scoped {
        for
          view    <- ConnectedRender.join(new RepeatedContactsFormExample)
          initial <- document(view)
          recoveredEvent = RepeatedContactsFormExample.Contacts.Definition.event(
            FormData(initialFields ++ row("contact-3", "Recovered", "recovered@example.com")),
            FormEventKind.Recovered
          )
          _         <- view.send(RepeatedContactsFormExample.Msg.Validate(recoveredEvent))
          _         <- view.click("[data-add-contact]")
          recovered <- document(view)
          _         <- view.clickButton("Reset")
          _ <- view.send(
                 RepeatedContactsFormExample.Msg.Remove(
                   RepeatedContactsFormExample.Contacts.key(99)
                 )
               )
          staleRemove <- document(view)
          _ <- view.changeForm(
                 "[data-contacts-form]",
                 duplicateNameFields,
                 target = Some("contact_book[contacts][contact-1][name]")
               )
          malformed <- document(view)
          _ <- view.changeForm(
                 "[data-contacts-form]",
                 row("contact-1", "Ada Lovelace", "ada@example.com") ++
                   row("contact-2", "Grace Hopper", "not-an-email"),
                 target = Some("contact_book[contacts][contact-2][email]")
               )
          invalid <- document(view)
          _       <- view.changeForm("[data-contacts-form]", initialFields)
          edited  <- document(view)
          _       <- view.click("[data-add-contact]")
          added   <- document(view)
          _ <- view.submitForm(
                 "[data-contacts-form]",
                 initialFields ++ row("contact-3", "", "")
               )
          invalidSubmit <- document(view)
          _       <- view.click("[data-contact-key=contact-3] [data-move-up]")
          moved   <- document(view)
          _       <- view.click("[data-contact-key=contact-1] [data-move-down]")
          movedAgain <- document(view)
          _       <- view.click("[data-contact-key=contact-1] [data-remove-row]")
          removed <- document(view)
          _       <- view.click("[data-add-contact]")
          next    <- document(view)
          _       <- view.click("[data-contact-key=contact-4] [data-remove-row]")
          finalFields =
            row("contact-3", "Katherine Johnson", "katherine@example.com") ++
              row("contact-2", "Grace Hopper", "grace@example.com")
          _     <- view.submitForm("[data-contacts-form]", finalFields)
          saved <- document(view)
          _     <- view.clickButton("Reset")
          reset <- document(view)
        yield assertTrue(
          initial.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-1", "contact-2"),
          initial.select("[data-summary-key]").eachAttr("data-summary-key").toArray.toVector ==
            Vector("contact-1", "contact-2"),
          recovered.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-1", "contact-2", "contact-3", "contact-4"),
          staleRemove.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-1", "contact-2"),
          malformed.select("[data-contact-key=contact-1] [data-contact-error=name]").text() ==
            "must be submitted at most once",
          invalid.select("[data-contact-key=contact-2] [data-contact-error=email]").text() ==
            "Enter a valid email address.",
          invalid.select("[data-contact-key=contact-1] .form-error").isEmpty,
          edited.select("[data-contact-error] .form-error").isEmpty,
          added.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-1", "contact-2", "contact-3"),
          added.select("[data-contact-key=contact-3] input[type=hidden]").size() == 1,
          invalidSubmit.select("[data-contact-key=contact-3] .form-error").size() == 2,
          invalidSubmit.select("[data-contacts-saved]").isEmpty,
          moved.select("[data-summary-key]").eachAttr("data-summary-key").toArray.toVector ==
            Vector("contact-1", "contact-3", "contact-2"),
          moved.select("[data-contact-key=contact-3] input[type=hidden]").attr("name") ==
            "contact_book[contacts][contact-3][_scalive_row]",
          moved.select("[data-contact-key=contact-3] input[type=hidden]").attr("id") ==
            "fa_n636f6e746163745f626f6f6b_n636f6e7461637473_r636f6e746163742d33_n5f7363616c6976655f726f77",
          movedAgain.select("[data-summary-key]").eachAttr("data-summary-key").toArray.toVector ==
            Vector("contact-3", "contact-1", "contact-2"),
          removed.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-3", "contact-2"),
          next.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-3", "contact-2", "contact-4"),
          saved.select("[data-contacts-saved]").text() ==
            "Saved 2 contacts in the displayed order.",
          saved.select("[data-summary-key]").eachAttr("data-summary-key").toArray.toVector ==
            Vector("contact-3", "contact-2"),
          saved.select("[data-summary-key=contact-3]").text() ==
            "contact-3 — Katherine Johnson",
          reset.select("[data-contact-key]").eachAttr("data-contact-key").toArray.toVector ==
            Vector("contact-1", "contact-2"),
          reset.select("[data-contacts-saved]").isEmpty,
          reset.select("[data-contact-error] .form-error").isEmpty
        )
      }
    }
  )
end RepeatedContactsFormExampleSpec
