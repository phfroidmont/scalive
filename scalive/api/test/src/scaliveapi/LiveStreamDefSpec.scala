package scaliveapi

import scala.util.Try

import zio.test.*

import scalive.streams.LiveStreamDef
import scalive.streams.StreamLimit

object LiveStreamDefSpec extends ZIOSpecDefault:
  override def spec = suite("LiveStreamDefSpec")(
    test("rejects an empty name and a non-positive limit at construction") {
      val items = LiveStreamDef.byId[Int, Int]("items")(identity)

      assertTrue(
        Try(LiveStreamDef.byId[Int, Int]("")(identity)).isFailure,
        Try(LiveStreamDef[Int, Int]("items", identity, _.toString, Some(StreamLimit.KeepLast(0))))
          .isFailure,
        Try(items.keepFirst(0)).isFailure,
        Try(items.keepLast(-1)).isFailure,
        items.keepLast(10).limit.contains(StreamLimit.KeepLast(10))
      )
    }
  )
