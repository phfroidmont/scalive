package scalive.defs.components

import scalive.*

import zio.test.*

object ComponentsSpec extends ZIOSpecDefault:

  private def uploadEntry(
    ref: String,
    preflighted: Boolean = false,
    done: Boolean = false,
    cancelled: Boolean = false
  ): LiveUploadEntry =
    LiveUploadEntry(
      ref = ref,
      clientName = s"$ref.txt",
      clientRelativePath = None,
      clientSize = 10,
      clientType = "text/plain",
      clientLastModified = None,
      progress = if done then 100 else 0,
      preflighted = preflighted,
      done = done,
      cancelled = cancelled,
      valid = true,
      errors = Nil,
      meta = None
    )

  private def liveUpload(
    autoUpload: Boolean,
    maxEntries: Int,
    entries: List[LiveUploadEntry]
  ): LiveUpload =
    LiveUpload(
      name = "avatar",
      ref = "phx-upload-ref",
      accept = LiveUploadAccept.Exactly(List(".jpg", ".png")),
      maxEntries = maxEntries,
      maxFileSize = 8_000_000,
      chunkSize = 64_000,
      chunkTimeout = 10_000,
      autoUpload = autoUpload,
      external = false,
      entries = entries,
      errors = Nil
    )

  override def spec = suite("ComponentsSpec")(
    test("focusWrap helper") {
      val el = focusWrap("dialog", cls := "wrapper")(
        button("Save"),
        button("Cancel")
      )

      val result = HtmlBuilder.build(el)

      assertTrue(
        result ==
          "<div id=\"dialog\" phx-hook=\"Phoenix.FocusWrap\" class=\"wrapper\"><span id=\"dialog-start\" tabindex=\"0\" aria-hidden=\"true\"></span><button>Save</button><button>Cancel</button><span id=\"dialog-end\" tabindex=\"0\" aria-hidden=\"true\"></span></div>"
      )
    },
    suite("liveFileInput helper")(
      test("does not render auto upload marker when disabled") {
        val upload = liveUpload(
          autoUpload = false,
          maxEntries = 1,
          entries = Nil
        )
        val el     = liveFileInput(upload)

        val result = HtmlBuilder.build(el)

        assertTrue(
          result.contains("id=\"phx-upload-ref\""),
          result.contains("accept=\".jpg,.png\""),
          result.contains("data-phx-hook=\"Phoenix.LiveFileUpload\""),
          !result.contains("data-phx-auto-upload"),
          !result.contains(" multiple")
        )
      },
      test("renders computed refs and presence attrs") {
        val upload = liveUpload(
          autoUpload = true,
          maxEntries = 2,
          entries = List(
            uploadEntry("entry-a"),
            uploadEntry("entry-b", cancelled = true),
            uploadEntry("entry-c", done = true),
            uploadEntry("entry-d", preflighted = true)
          )
        )
        val el     = liveFileInput(upload)

        val result = HtmlBuilder.build(el)

        assertTrue(
          result.contains("data-phx-active-refs=\"entry-a,entry-c,entry-d\""),
          result.contains("data-phx-done-refs=\"entry-c\""),
          result.contains("data-phx-preflighted-refs=\"entry-c,entry-d\""),
          result.contains("data-phx-auto-upload"),
          !result.contains("data-phx-auto-upload=\"false\""),
          result.contains(" multiple")
        )
      }
    )
  )
