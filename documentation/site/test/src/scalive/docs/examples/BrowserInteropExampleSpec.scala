package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.testing.{ConnectedRender, ConnectedView}

object BrowserInteropExampleSpec extends ZIOSpecDefault:
  private def document(harness: ConnectedView[?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("BrowserInteropExampleSpec")(
    test("requests clipboard work and resets deterministically") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new BrowserInteropExample("first"))
          initial <- document(harness)
          _       <- harness.clickButton("Copy sample text")
          pending <- document(harness)
          _       <- harness.send(BrowserInteropExample.Msg.Reset)
          reset   <- document(harness)
        yield assertTrue(
          initial.select("[data-browser-copy-status]").text() == "No browser operation requested yet.",
          pending.select("[data-browser-copy-status]").text() == "Waiting for the browser result. Retry if needed.",
          reset.select("[data-browser-copy-status]").text() == "No browser operation requested yet."
        )
      }
    },
    test("accepts only the current correlated browser result") {
      val pending = BrowserInteropExample.Model(
        requestNumber = 2,
        operation = BrowserInteropExample.CopyOperation.Pending("copy-2")
      )
      val stale = BrowserInteropExample.applyCopyResult(
        pending,
        BrowserInteropExample.CopyResult("copy-1", ok = true)
      )
      val succeeded = BrowserInteropExample.applyCopyResult(
        pending,
        BrowserInteropExample.CopyResult("copy-2", ok = true)
      )
      val failed = BrowserInteropExample.applyCopyResult(
        pending,
        BrowserInteropExample.CopyResult("copy-2", ok = false)
      )
      assertTrue(
        stale == pending,
        succeeded.operation == BrowserInteropExample.CopyOperation.Succeeded,
        failed.operation == BrowserInteropExample.CopyOperation.Failed
      )
    },
    test("derives hook and command ids from the example instance") {
      ZIO.scoped {
        for
          first  <- ConnectedRender.join(new BrowserInteropExample("first"))
          second <- ConnectedRender.join(new BrowserInteropExample("second"))
          firstDocument  <- document(first)
          secondDocument <- document(second)
          firstIds = firstDocument.select("[id]").eachAttr("id")
          secondIds = secondDocument.select("[id]").eachAttr("id")
        yield assertTrue(
          firstDocument.select("[phx-hook=BrowserInterop][id=first-hook]").size() == 1,
          secondDocument.select("[phx-hook=BrowserInterop][id=second-hook]").size() == 1,
          firstIds.stream().noneMatch(secondIds.contains)
        )
      }
    }
  )
end BrowserInteropExampleSpec
