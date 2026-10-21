package uploads

import java.nio.charset.StandardCharsets

import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object TextUploadLiveViewSpec extends ZIOSpecDefault:
  def spec = suite("Text uploads")(
    test("Save is enabled while a file is selected, and saving consumes it") {
      def saveDisabled(view: scalive.testing.ConnectedView[?]) =
        view.selectOne("button[type=submit]").map(_.attribute("disabled").isDefined)

      ZIO.scoped {
        for
          view   <- ConnectedRender.join(TextUploadLiveView())
          before <- saveDisabled(view)
          _      <- view.upload(
                 TextFiles,
                 "notes.txt",
                 "text/plain",
                 Chunk.fromArray("hello".getBytes(StandardCharsets.UTF_8))
               )
          _        <- view.awaitHtml("file selected", 3.seconds)(_.contains("notes.txt"))
          selected <- saveDisabled(view)
          _        <- view.submitForm("form", Vector.empty)
          after    <- saveDisabled(view)
        yield assertTrue(before, !selected, after)
      }
    }
  )
end TextUploadLiveViewSpec
