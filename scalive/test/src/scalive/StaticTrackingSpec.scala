package scalive

import zio.json.ast.Json
import zio.test.*

object StaticTrackingSpec extends ZIOSpecDefault:
  override def spec = suite("StaticTrackingSpec")(
    test("collects href and src from tracked tags") {
      val el = div(
        scriptTag(phx.trackStatic := true, src  := "/static/app.js"),
        linkTag(phx.trackStatic   := true, href := "/static/app.css"),
        div()
      )

      val urls = StaticTracking.collect(el)
      assertTrue(urls == List("/static/app.js", "/static/app.css"))
    },
    test("extracts _track_static from params") {
      val params = Map("_track_static" -> Json.Arr(Json.Str("/a.js"), Json.Str("/b.css")))
      assertTrue(
        StaticTracking.clientListFromParams(Some(params)) == Some(List("/a.js", "/b.css")),
        StaticTracking.clientListFromParams(None).isEmpty
      )
    },
    test("detects static changes when lists differ") {
      val server = List("/a.js", "/b.css")
      assertTrue(
        !StaticTracking.staticChanged(Some(server), server),
        StaticTracking.staticChanged(Some(List("/a.js")), server),
        !StaticTracking.staticChanged(None, server)
      )
    },
    test("normalizes absolute URLs and query strings before comparing") {
      val client = List("http://localhost/static/app-123.js?vsn=d", "http://localhost/static/app-123.css")
      val server = List("/static/app-123.js", "/static/app-123.css")

      assertTrue(!StaticTracking.staticChanged(Some(client), server))
    },
    test("treats an empty client tracking list as unchanged") {
      assertTrue(!StaticTracking.staticChanged(Some(Nil), List("/static/app.js")))
    }
  )
