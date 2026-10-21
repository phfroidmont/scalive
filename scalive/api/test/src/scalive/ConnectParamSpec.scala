package scalive

import java.time.ZoneId

import zio.json.ast.Json
import zio.test.*

object ConnectParamSpec extends ZIOSpecDefault:
  private def metadata(params: (String, Json)*): ConnectedMetadata =
    new ConnectedMetadata:
      val staticChanged = false
      private[scalive] val connectParams = params.toMap

  override def spec = suite("ConnectParamSpec")(
    test("decodes a typed connect param, and is absent when missing or undecodable") {
      val timeZone = ConnectParam[ZoneId]("timeZone")

      assertTrue(
        metadata("timeZone" -> Json.Str("Europe/Brussels")).connectParam(timeZone)
          .contains(ZoneId.of("Europe/Brussels")),
        metadata().connectParam(timeZone).isEmpty,
        metadata("timeZone" -> Json.Str("Not/AZone")).connectParam(timeZone).isEmpty,
        metadata("timeZone" -> Json.Num(3)).connectParam(timeZone).isEmpty
      )
    },
    test("a positive _mounts counter is a rejoin, anything else the first join") {
      assertTrue(
        metadata("_mounts" -> Json.Num(1)).join == ConnectedJoin.Rejoin,
        metadata("_mounts" -> Json.Num(0)).join == ConnectedJoin.First,
        metadata("_mounts" -> Json.Num(-1)).join == ConnectedJoin.First,
        metadata("_mounts" -> Json.Str("2")).join == ConnectedJoin.First,
        metadata().join == ConnectedJoin.First
      )
    },
    test("rejects names Phoenix reserves") {
      assertTrue(
        scala.util.Try(ConnectParam[Long]("_mounts")).isFailure,
        scala.util.Try(ConnectParam[String]("")).isFailure
      )
    }
  )
end ConnectParamSpec
