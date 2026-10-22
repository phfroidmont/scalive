package scalive

import java.time.LocalDate
import java.util.UUID

import zio.schema.{Schema, derived}
import zio.schema.annotation.caseName
import zio.test.*

object FormFieldsSpec extends ZIOSpecDefault:
  enum Status derives Schema:
    @caseName("open") case Open
    @caseName("paid") case Paid

  final case class Period(days: Int = 7, from: Option[LocalDate] = None) derives Schema

  final case class Filters(
    status: Option[Status],
    practitioners: Set[UUID],
    page: Int = 1,
    period: Period)
      derives Schema

  final case class Required(name: String, page: Int = 1) derives Schema

  private val ada = UUID.fromString("00000000-0000-0000-0000-00000000000a")
  private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

  private def data(fields: (String, String)*) = FormData(fields)

  override def spec = suite("FormFieldsSpec")(
    test("decodes fields like a typed query: enums by name, sets from repeated names") {
      val decode = SchemaQuery.formFields(Schema[Filters])

      assertTrue(
        decode(
          data(
            "status"        -> "paid",
            "practitioners" -> ada.toString,
            "practitioners" -> bob.toString,
            "page"          -> "3",
            "period.days"   -> "14",
            "_target"       -> "status",
            "_unused_page"  -> "",
            "other"         -> "ignored"
          )
        ) == Filters(Some(Status.Paid), Set(ada, bob), 3, Period(14, None))
      )
    },
    test("missing and malformed fields take their fallback, so decoding never fails") {
      val decode = SchemaQuery.formFields(Schema[Filters])

      assertTrue(
        decode(data()) == Filters(None, Set.empty, 1, Period()),
        decode(
          data(
            "status"        -> "lost",
            "practitioners" -> "not-a-uuid",
            "practitioners" -> ada.toString,
            "page"          -> "x",
            "period.from"   -> "yesterday"
          )
        ) == Filters(None, Set(ada), 1, Period())
      )
    },
    test("a field without a fallback is rejected when the binding is built") {
      val error = scala.util.Try(SchemaQuery.formFields(Schema[Required])).failed.toOption

      assertTrue(error.exists(_.getMessage.contains("Form field name needs a fallback")))
    },
    test("one field decodes with its default, or as an option or a collection") {
      val days      = SchemaQuery.formField("days", Schema[Int], Some(7))
      val status    = SchemaQuery.formField("status", Schema[Option[Status]], None)
      val selected  = SchemaQuery.formField("ids", Schema[List[UUID]], None)
      val noDefault = scala.util.Try(SchemaQuery.formField("days", Schema[Int], None)).failed.toOption

      assertTrue(
        days(data("days" -> "14")) == 14,
        days(data("days" -> "fourteen")) == 7,
        days(data()) == 7,
        status(data("status" -> "open")).contains(Status.Open),
        selected(data("ids" -> bob.toString, "ids" -> ada.toString)) == List(bob, ada),
        noDefault.exists(_.getMessage.contains("Form field days needs a fallback"))
      )
    },
    test("a missing option or collection takes its declared default") {
      final case class Saved(
        status: Option[Status] = Some(Status.Open),
        ids: List[UUID] = List(ada),
        period: Period = Period(days = 30))
          derives Schema
      val decode = SchemaQuery.formFields(Schema[Saved])
      val status = SchemaQuery.formField("status", Schema[Option[Status]], Some(Some(Status.Paid)))

      assertTrue(
        decode(data()) == Saved(),
        decode(data("status" -> "", "ids" -> "", "period.days" -> "7")) ==
          Saved(None, Nil, Period()),
        status(data()).contains(Status.Paid)
      )
    }
  )
end FormFieldsSpec
