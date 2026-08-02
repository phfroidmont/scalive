package scalive.defs.components

import scalive.*
import scalive.upload.{UploadEntryRef, UploadRef}

import zio.*
import zio.test.*

object ComponentsSpec extends ZIOSpecDefault:
  private def uploadEntry(
    ref: String,
    status: LiveUploadEntryStatus = LiveUploadEntryStatus.Selected
  ): LiveUploadEntry[Unit] =
    new LiveUploadEntry(
      UploadEntryRef(ref),
      new UploadClientMetadata(s"$ref.txt", None, 10, "text/plain", None, None),
      status,
      None,
      "avatar"
    )

  private def liveUpload(
    autoUpload: Boolean,
    maxEntries: Int,
    entries: List[LiveUploadEntry[Unit]]
  ): LiveUpload[Unit] =
    val definition = LiveUploadDef.hosted(
      name = "avatar",
      accept = LiveUploadAccept.only(".jpg", ".png"),
      writer = new LiveUploadWriter[Unit, Unit]:
        def init(client: UploadClientMetadata) = ZIO.unit
        def writeChunk(data: Chunk[Byte], state: Unit) = ZIO.unit
        def complete(state: Unit) = ZIO.unit
        def abort(state: Unit, reason: LiveUploadAbortReason) = ZIO.unit
        def discard(result: Unit) = ZIO.unit,
      maxEntries = maxEntries,
      autoUpload = autoUpload
    )
    new LiveUpload(
      definition,
      UploadRef("phx-upload-ref"),
      entries,
      Nil
    )

  override def spec = suite("ComponentsSpec")(
    test("focusWrap helper") {
      val result = HtmlBuilder.build(focusWrap("dialog", cls := "wrapper")(button("Save")))
      assertTrue(result.contains("phx-hook=\"Phoenix.FocusWrap\""))
    },
    suite("liveFileInput helper")(
      test("renders definition attributes") {
        val result = HtmlBuilder.build(liveFileInput(liveUpload(false, 1, Nil)))
        assertTrue(
          result.contains("id=\"phx-upload-ref\""),
          result.contains("accept=\".jpg,.png\""),
          !result.contains("data-phx-auto-upload"),
          !result.contains(" multiple")
        )
      },
      test("derives wire refs from entry status") {
        val upload = liveUpload(
          true,
          4,
          List(
            uploadEntry("selected"),
            uploadEntry("preflighted", LiveUploadEntryStatus.Preflighted),
            uploadEntry("uploading", LiveUploadEntryStatus.Uploading(50)),
            uploadEntry("done", LiveUploadEntryStatus.Completed)
          )
        )
        val result = HtmlBuilder.build(liveFileInput(upload))
        assertTrue(
          result.contains("data-phx-active-refs=\"selected,preflighted,uploading,done\""),
          result.contains("data-phx-done-refs=\"done\""),
          result.contains("data-phx-preflighted-refs=\"preflighted,uploading,done\""),
          result.contains("data-phx-auto-upload"),
          result.contains(" multiple")
        )
      }
    )
  )
