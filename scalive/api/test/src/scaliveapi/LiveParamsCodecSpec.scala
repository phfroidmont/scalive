package scaliveapi

import java.time.LocalDate
import java.util.UUID

import zio.http.URL
import zio.http.codec.HttpCodec
import zio.schema.{Schema, derived}
import zio.schema.annotation.caseName
import zio.test.*

import scalive.*

object LiveParamsCodecSpec extends ZIOSpecDefault:
  private def url(value: String): URL = URL.decode(value).toOption.get

  enum Mode derives Schema:
    @caseName("agenda") case Agenda
    @caseName("schedule") case Schedule

  final case class Range(days: Int = 7, date: Option[LocalDate] = None) derives Schema

  final case class Filters(
    q: Option[String] = None,
    page: Int = 1,
    ids: List[UUID] = Nil,
    mode: Mode = Mode.Agenda,
    back: Range = Range())
      derives Schema

  final case class Required(id: UUID, page: Int = 1) derives Schema

  final case class OptionalRange(range: Option[Range]) derives Schema

  private val first  = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

  override def spec = suite("LiveParamsCodecSpec")(
    test("a query value the codec rejects fails with a decode error") {
      val optional = LiveParamsCodec.fromQuery[Unit, Option[UUID]](
        HttpCodec.query[UUID]("id").optional
      )
      val required = LiveParamsCodec.fromQuery[Unit, Int](HttpCodec.query[Int]("page"))
      val id       = UUID.randomUUID()

      for
        malformed <- optional.decode((), url("/reset?id=garbage")).exit
        missing   <- optional.decode((), url("/reset"))
        present   <- optional.decode((), url(s"/reset?id=$id"))
        absent    <- required.decode((), url("/list")).exit
      yield assertTrue(
        malformed.causeOption.flatMap(_.failureOption).exists(_.message.contains("id")),
        missing.isEmpty,
        present.contains(id),
        absent.causeOption.flatMap(_.failureOption).exists(_.message.contains("page"))
      )
    },
    test("a schema query decodes every field, including enums, lists and nested records") {
      val codec = LiveParamsCodec.query[Filters]
      val full  = Filters(
        q = Some("knee"),
        page = 2,
        ids = List(first, second),
        mode = Mode.Schedule,
        back = Range(3, Some(LocalDate.of(2026, 10, 21)))
      )
      val href =
        s"/x?q=knee&page=2&ids=$first&ids=$second&mode=schedule&back.days=3&back.date=2026-10-21"

      for
        decoded   <- codec.decode((), url(href))
        encoded    = codec.encode(full).map(_.queryParams)
        roundTrip <- codec.decode((), url(s"/x${encoded.toOption.get.encode}"))
      yield assertTrue(decoded == full, roundTrip == full)
    },
    test("a missing or malformed field with a fallback takes it") {
      val codec = LiveParamsCodec.query[Filters]
      val href  =
        s"/x?page=x&ids=$first&ids=garbage&mode=other&back.days=3&back.date=2026-13-01"

      for
        empty     <- codec.decode((), url("/x"))
        malformed <- codec.decode((), url(href))
        repeated  <- codec.decode((), url("/x?page=2&page=3"))
      yield assertTrue(
        empty == Filters(),
        malformed == Filters(ids = List(first), back = Range(days = 3)),
        repeated == Filters()
      )
    },
    test("encoding leaves out defaults, so the default value is the bare path") {
      val codec = LiveParamsCodec.query[Filters]

      assertTrue(
        codec.encode(Filters()).map(_.queryParams.encode) == Right(""),
        codec.encode(Filters(page = 2, back = Range(days = 3))).map(_.queryParams.encode) ==
          Right("?page=2&back.days=3")
      )
    },
    test("a field with no fallback still fails when missing or malformed") {
      val codec = LiveParamsCodec.query[Required]

      for
        missing   <- codec.decode((), url("/x")).exit
        malformed <- codec.decode((), url("/x?id=garbage")).exit
        fallback  <- codec.decode((), url(s"/x?id=$first&page=x"))
      yield assertTrue(
        missing.causeOption.flatMap(_.failureOption).exists(_.message.contains("id")),
        malformed.causeOption.flatMap(_.failureOption).exists(_.message.contains("id")),
        fallback == Required(first)
      )
    },
    test("a field shape a query cannot carry is rejected when the codec is built") {
      val failure = scala.util.Try(LiveParamsCodec.query[OptionalRange]).failed.toOption

      assertTrue(failure.exists(_.getMessage.contains("range")))
    }
  )
end LiveParamsCodecSpec
