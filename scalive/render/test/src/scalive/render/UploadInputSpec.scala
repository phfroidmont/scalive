package scalive.render

import zio.*
import zio.test.*

import scalive.*

object UploadInputSpec extends ZIOSpecDefault:
  private def upload(name: String): LiveUpload[Chunk[Byte]] =
    new LiveUpload(LiveUploadDef.inMemory(name, LiveUploadAccept.Any), UploadRef(s"phx-$name"), Nil, Nil)

  private val avatar = upload("avatar")
  private val cover  = upload("cover")

  private def render(view: Signal[(LiveUpload[Chunk[Byte]], LiveUpload[Chunk[Byte]])] => HtmlElement[String]) =
    for
      program   <- ZIO.fromEither(RenderProgram.compile(view))
      evaluated <- program.evaluate((avatar, cover))
    yield HtmlRenderer.render(evaluated.commit.tree)

  def spec = suite("UploadInputSpec")(
    test("renders an upload's progress binding on its own file input") {
      for html <- render(uploads =>
                    div(
                      liveFileInput(uploads.map(_._1), uploads.map(_._1).onProgress("progress")),
                      liveFileInput(uploads.map(_._2), uploads.map(_._2).onProgress(_ => "progress"))
                    )
                  )
      yield assertTrue(
        "<input [^>]*phx-progress=".r.findAllMatchIn(html).size == 2
      )
    },
    test("rejects another upload's progress binding") {
      for signal <- render(uploads =>
                      div(liveFileInput(uploads.map(_._1), uploads.map(_._2).onProgress("progress")))
                    ).exit
      yield assertTrue(
        signal.isFailure
      )
    }
  )
end UploadInputSpec
