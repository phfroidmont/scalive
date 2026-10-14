package scalive.render

import zio.*
import zio.test.*

import scalive.*
import scalive.streams.*

object ManagedStateSpec extends ZIOSpecDefault:
  final private case class Item(id: String, label: String)

  private val Files  = LiveUploadDef.inMemory("files", LiveUploadAccept.Any)
  private val Items  = LiveStreamDef.byId[Item, String]("items")(_.id)
  private val upload = new LiveUpload(Files, UploadRef("phx-files"), Nil, Nil)
  private val items  = LiveStream(
    LiveStreamIdentity.fresh(),
    Items.name,
    generation = 1L,
    entries = Vector(LiveStreamEntry("items-a", Item("a", "one"))),
    inserted = Vector.empty,
    deleted = Vector.empty,
    reset = false
  )

  final private case class TestState(
    uploads: Map[String, LiveUpload[?]] = Map.empty,
    streams: Map[String, LiveStream[?]] = Map.empty)
      extends RenderState:
    val flash = Map.empty
    def upload[R](definition: LiveUploadDef[R]): Option[LiveUpload[R]] =
      uploads.get(definition.name).map(_.asInstanceOf[LiveUpload[R]])
    def stream[A](definition: LiveStreamDef[A, ?]): Option[LiveStream[A]] =
      streams.get(definition.name).map(_.asInstanceOf[LiveStream[A]])

  private def render(view: HtmlElement[Nothing], state: TestState) =
    for
      program   <- ZIO.fromEither(RenderProgram.compile[TestState, Nothing](_ => view, identity))
      evaluated <- program.evaluate(state)
    yield HtmlRenderer.render(evaluated.commit.tree)

  def spec = suite("ManagedStateSpec")(
    test("an upload renders from the owner's state, and nothing while it is not allowed") {
      val view = div(Files.render(upload => liveFileInput(upload)))
      for
        allowed    <- render(view, TestState(uploads = Map(Files.name -> upload)))
        notAllowed <- render(view, TestState())
      yield assertTrue(
        allowed.contains("data-phx-upload-ref=\"phx-files\""),
        !notAllowed.contains("input")
      )
    },
    test("an upload body may render content other than one element, but not attributes") {
      val siblings = div(
        Files.render(upload => liveFileInput(upload)),
        Files.render(upload => upload.map(_.ref.value))
      )
      for
        rendered  <- render(siblings, TestState(uploads = Map(Files.name -> upload)))
        attribute <- render(div(Files.render(_.dropTarget)), TestState()).exit
      yield assertTrue(
        rendered.contains("data-phx-upload-ref=\"phx-files\""),
        rendered.endsWith(">phx-files</div>"),
        attribute.causeOption.exists(_.prettyPrint.contains("must render content"))
      )
    },
    test("a stream renders from the owner's state and fails when the owner never created it") {
      val view = div(Items.renderIn(ul)(item => li(item.map(_.label))))
      for
        created <- render(view, TestState(streams = Map(Items.name -> items)))
        missing <- render(view, TestState()).exit
      yield assertTrue(
        created.contains("<ul id=\"items\" phx-update=\"stream\"><li id=\"items-a\">one</li></ul>"),
        missing.causeOption.exists(_.prettyPrint.contains("stream 'items' was not created"))
      )
    }
  )
end ManagedStateSpec
