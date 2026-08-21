package scalive.docs.examples

import java.nio.charset.StandardCharsets

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.testing.{ConnectedRender, ConnectedView}

object TextUploadExampleSpec extends ZIOSpecDefault:
  private val SecretText = "internal launch phrase alpha beta\nsecond line"

  private def document(harness: ConnectedView[?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  private def uploadRef(harness: ConnectedView[?]): Task[String] =
    document(harness).flatMap { page =>
      ZIO.attempt {
        val input = page.selectFirst("[data-text-upload-input]")
        if input == null then throw new RuntimeException("Upload input was not rendered.")
        val ref = input.attr("data-phx-upload-ref")
        if ref.isEmpty then throw new RuntimeException("Upload input has no protocol reference.")
        ref
      }
    }

  override def spec = suite("TextUploadExampleSpec")(
    test("consumes a bounded text upload into aggregate facts without retaining its content") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new TextUploadExample)
          ref     <- uploadRef(harness)
          bytes    = Chunk.fromArray(SecretText.getBytes(StandardCharsets.UTF_8))
          _       <- harness.upload(ref, "entry-1", "notes.txt", "text/plain", bytes)
          _       <- harness.submitForm("[data-text-upload-form]", Vector.empty)
          page    <- document(harness)
          html    <- harness.html
        yield assertTrue(
          page.select("[data-upload-summary]").size() == 1,
          page.select("[data-upload-summary] [data-summary-name]").text() == "notes.txt",
          page.select("[data-upload-summary] [data-summary-lines]").text() == "2 lines",
          page.select("[data-upload-summary] [data-summary-words]").text() == "7 words",
          page.select("[data-text-upload-input]").attr("data-phx-active-refs").isEmpty,
          !html.contains(SecretText),
          !html.contains("internal launch phrase")
        )
      }
    },
    test("reset clears summaries and replaces active upload state") {
      ZIO.scoped {
        for
          harness   <- ConnectedRender.join(new TextUploadExample)
          initialRef <- uploadRef(harness)
          _ <- harness.upload(
                 initialRef,
                 "entry-1",
                 "notes.md",
                 "text/markdown",
                 Chunk.fromArray("one two".getBytes(StandardCharsets.UTF_8))
               )
          _        <- harness.submitForm("[data-text-upload-form]", Vector.empty)
          _        <- harness.send(TextUploadExample.Msg.Reset)
          resetRef <- uploadRef(harness)
          page     <- document(harness)
        yield assertTrue(
          page.select("[data-upload-summary]").isEmpty,
          resetRef.nonEmpty,
          resetRef != initialRef
        )
      }
    },
    test("keeps summaries isolated between LiveView instances") {
      ZIO.scoped {
        for
          first     <- ConnectedRender.join(new TextUploadExample)
          second    <- ConnectedRender.join(new TextUploadExample)
          firstRef  <- uploadRef(first)
          _ <- first.upload(
                 firstRef,
                 "entry-1",
                 "first.txt",
                 "text/plain",
                 Chunk.fromArray("isolated words".getBytes(StandardCharsets.UTF_8))
               )
          _          <- first.submitForm("[data-text-upload-form]", Vector.empty)
          firstPage  <- document(first)
          secondPage <- document(second)
        yield assertTrue(
          firstPage.select("[data-upload-summary]").size() == 1,
          secondPage.select("[data-upload-summary]").isEmpty
        )
      }
    }
  )
end TextUploadExampleSpec
