package scaliveapi

import java.util.UUID

import zio.http.URL
import zio.http.codec.HttpCodec
import zio.test.*

import scalive.LiveParamsCodec

object LiveParamsCodecSpec extends ZIOSpecDefault:
  private def url(value: String): URL = URL.decode(value).toOption.get

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
    }
  )
end LiveParamsCodecSpec
