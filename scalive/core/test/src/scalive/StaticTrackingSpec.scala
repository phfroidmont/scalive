package scalive

import utest.*
import zio.json.ast.Json

object StaticTrackingSpec extends TestSuite:
  val tests = Tests {
    test("collects href and src from tracked tags") {
      val el = div(
        scriptTag(phx.trackStatic := true, src  := "/static/app.js"),
        linkTag(phx.trackStatic   := true, href := "/static/app.css"),
        div()
      )

      val urls = StaticTracking.collect(el)
      assert(urls == List("/static/app.js", "/static/app.css"))
    }

    test("extracts _track_static from params") {
      val params = Map("_track_static" -> Json.Arr(Json.Str("/a.js"), Json.Str("/b.css")))
      assert(StaticTracking.clientListFromParams(Some(params)) == Some(List("/a.js", "/b.css")))
      assert(StaticTracking.clientListFromParams(None).isEmpty)
    }

    test("detects static changes when lists differ") {
      val server = List("/a.js", "/b.css")
      assert(!StaticTracking.staticChanged(Some(server), server))
      assert(StaticTracking.staticChanged(Some(List("/a.js")), server))
      assert(!StaticTracking.staticChanged(None, server))
    }
  }
