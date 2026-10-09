package quickstart

import zio.*
import zio.test.*

import scalive.testing.{ConnectedRender, HtmlQueryError, RenderedHtml}

object CounterSnapshotSpec extends ZIOSpecDefault:
  def spec = suite("Counter snapshots")(
    test("retained HTML stays queryable after the view closes") {
      // docs:start testing-snapshots
      for
        retained <- ZIO.scoped {
                      for
                        view   <- ConnectedRender.join(CounterLiveView())
                        before <- view.html
                        _      <- view.clickButton("Increase")
                        after  <- view.html
                      yield (before, after)
                    }
      yield
        // The connected view is already closed; both strings remain queryable.
        val before = RenderedHtml.parse(retained._1)
        val after  = RenderedHtml.parse(retained._2)
        assertTrue(
          before.html == retained._1,
          before.selectOne("output").map(_.text) == Right("0"),
          after.selectOne("output").map(_.text) == Right("1"),
          before.selectOne("#missing") == Left(HtmlQueryError.NotFound("#missing")),
          after.selectAll("#missing").map(_.isEmpty) == Right(true)
        )
      // docs:end testing-snapshots
    }
  )
