import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*

import scalive.*
import scalive.codecs.StringAsIsEncoder

object RootSliceApp extends ZIOAppDefault:
  private val defaultPort   = 8081
  private val mountSequence = AtomicInteger(0)

  private val serverPort =
    sys.env
      .get("SCALIVE_SERVER_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  private val config = ZioHttpConfig(
    signingSecret = "root-slice-test-only-signing-secret-32-bytes",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(
      WebSocketOrigin.http("localhost", serverPort),
      WebSocketOrigin.http("127.0.0.1", serverPort)
    )
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val navigationA           = live / "nav" / "a"
  private val navigationB           = live / "nav" / "b"
  private val navigationC           = live / "nav" / "c"
  private val navigationD           = live / "nav" / "d"
  private val upstreamNavigationA   = live / "navigation" / "a"
  private val upstreamNavigationB   = live / "navigation" / "b"
  private val upstreamStream        = live / "stream"
  private val uploadRoute           = live / "upload"
  private val nestedRoute           = live / "nested"
  private val nestedStickyA         = live / "nested" / "a"
  private val nestedStickyB         = live / "nested" / "b"
  private val navigationGuard       = (live / "navigation-guard").queryOptional[String]("step")
  private val navigationGuardTarget = live / "navigation-guard" / "target"
  private val redirectLoop          = (live / "navigation" / "redirectloop").paramsDecodeOnly(
    LiveParamsDecoder.custom[Unit, Boolean]((_, url) =>
      Right(url.queryParam("loop").contains("true"))
    )
  )
  private val issue3686A = live / "issues" / "3686" / "a"
  private val issue3686B = live / "issues" / "3686" / "b"
  private val issue3686C = live / "issues" / "3686" / "c"

  private def rootOne(navigationGuardAssets: NavigationGuardAssets) =
    LiveRootLayout[Any, Any]("root-one")([Msg] =>
      (content, _, _) =>
        htmlRootTag(
          headTag(
            navigationGuardAssets.script,
            scriptTag(src := "/app.js", defer := true)
          ),
          bodyTag(idAttr := "root-one", content)
        )
    )

  private def rootTwo(navigationGuardAssets: NavigationGuardAssets) =
    LiveRootLayout[Any, Any]("root-two")([Msg] =>
      (content, _, _) =>
        htmlRootTag(
          headTag(
            navigationGuardAssets.script,
            scriptTag(src := "/app.js", defer := true)
          ),
          bodyTag(idAttr := "root-two", content)
        )
    )

  private def application(navigationGuardAssets: NavigationGuardAssets) =
    val firstRoot  = rootOne(navigationGuardAssets)
    val secondRoot = rootTwo(navigationGuardAssets)

    Live.router.withRootLayout(firstRoot)(
      live        -> RootSliceLiveView(mountSequence),
      uploadRoute -> HostedUploadLiveView(),
      nestedRoute -> NestedParentLiveView(),
      Live.session("nested-sticky")(
        nestedStickyA -> StickyNestedParentLiveView("a", nestedStickyB.location),
        nestedStickyB -> StickyNestedParentLiveView("b", nestedStickyA.location)
      ),
      Live.session("navigation")(
        navigationA -> NavigationLiveView(
          "a",
          mountSequence,
          navigationA.location,
          navigationB.location,
          navigationC.location,
          navigationD.location
        ),
        navigationB -> NavigationLiveView(
          "b",
          mountSequence,
          navigationA.location,
          navigationB.location,
          navigationC.location,
          navigationD.location
        ),
        navigationD.withRootLayout(secondRoot) -> NavigationLiveView(
          "d",
          mountSequence,
          navigationA.location,
          navigationB.location,
          navigationC.location,
          navigationD.location
        )
      ),
      Live
        .session("other").withRootLayout(secondRoot)(
          navigationC -> NavigationLiveView(
            "c",
            mountSequence,
            navigationA.location,
            navigationB.location,
            navigationC.location,
            navigationD.location
          )
        ),
      Live.session("upstream-navigation")(
        upstreamNavigationA -> UpstreamNavigationLiveView(
          "a",
          upstreamNavigationA.location,
          upstreamNavigationB.location,
          upstreamStream.location
        ),
        upstreamNavigationB -> UpstreamNavigationLiveView(
          "b",
          upstreamNavigationA.location,
          upstreamNavigationB.location,
          upstreamStream.location
        ),
        redirectLoop -> RedirectLoopLiveView()
      ),
      Live.session("upstream-other")(
        upstreamStream -> UpstreamNavigationLiveView(
          "stream",
          upstreamNavigationA.location,
          upstreamNavigationB.location,
          upstreamStream.location
        )
      ),
      Live.session("issue-3686")(
        issue3686A -> Issue3686LiveView(
          "A",
          issue3686A.location,
          issue3686B.location,
          issue3686C.location
        ),
        issue3686B -> Issue3686LiveView(
          "B",
          issue3686A.location,
          issue3686B.location,
          issue3686C.location
        )
      ),
      Live.session("issue-3686-other")(
        issue3686C -> Issue3686LiveView(
          "C",
          issue3686A.location,
          issue3686B.location,
          issue3686C.location
        )
      ),
      Live.session("navigation-guard-source")(
        navigationGuard -> NavigationGuardLiveView(
          navigationGuard.location(Some("patched")),
          navigationGuardTarget.location
        )
      ),
      Live.session("navigation-guard-target")(
        navigationGuardTarget -> NavigationGuardTargetLiveView
      )
    )
  end application

  private val supportRoutes = Routes(
    Method.GET / "health"      -> handler(Response.text("OK")),
    Method.GET / "favicon.ico" -> handler(Response(status = Status.NoContent)),
    Method.GET / "app.js"      -> handler {
      ZIO
        .attemptBlocking {
          val stream = Option(getClass.getClassLoader.getResourceAsStream("public/app.js"))
            .getOrElse(throw IllegalStateException("Missing bundled public/app.js"))
          try stream.readAllBytes()
          finally stream.close()
        }.fold(
          _ => Response.internalServerError,
          bytes =>
            Response(
              headers = Headers(Header.ContentType(MediaType.application.javascript)),
              body = Body.fromArray(bytes)
            )
        )
    }
  )

  override val run =
    for
      navigationGuardAssets <- NavigationGuardAssets.load()
      routes = ZioHttp.routes(application(navigationGuardAssets), config) ++ supportRoutes ++
                 navigationGuardAssets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(serverPort))
    yield ()
end RootSliceApp

final class RootSliceLiveView(mountSequence: AtomicInteger)
    extends LiveView[RootSliceLiveView.Msg.type, Int]:
  import RootSliceLiveView.*

  def mount(ctx: MountContext) =
    val sequence = mountSequence.incrementAndGet()
    ZIO.succeed(sequence)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg => ZIO.succeed(model + 1)

  override def view(model: Signal[Int]) =
    val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

    mainTag(
      h1("Root slice counter"),
      span(
        idAttr    := "counter-value",
        ariaLabel := "Counter value",
        model.map(_.toString)
      ),
      button(on.click(Msg), "Increment")
    )

object RootSliceLiveView:
  case object Msg

final class NestedParentLiveView extends LiveView[NestedParentLiveView.Msg.type, Boolean]:
  import NestedParentLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(true)

  def handleMessage(model: Boolean, ctx: MessageContext) =
    case Msg => ZIO.succeed(!model)

  def view(model: Signal[Boolean]) =
    mainTag(
      idAttr := "nested-parent-content",
      button(on.click(Msg), "Toggle child"),
      div(Signal.when(model)(div(liveView("nested-child", NestedChildLiveView()))))
    )

object NestedParentLiveView:
  case object Msg

final class NestedChildLiveView extends LiveView[NestedChildLiveView.Msg.type, Int]:
  import NestedChildLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg => ZIO.succeed(model + 1)

  def view(model: Signal[Int]) =
    sectionTag(
      idAttr := "nested-child-content",
      span(idAttr := "nested-child-counter", model.map(_.toString)),
      button(on.click(Msg), "Increment child"),
      liveView("nested-grandchild", NestedGrandchildLiveView)
    )

object NestedChildLiveView:
  case object Msg

object NestedGrandchildLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext)  = ZIO.succeed(())
  def view(model: Signal[Unit]) = asideTag(idAttr := "nested-grandchild-content", "grandchild")

final class StickyNestedParentLiveView(page: String, destination: LiveLocation)
    extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.succeed(())

  def view(model: Signal[Unit]) =
    mainTag(
      idAttr := s"sticky-parent-$page",
      link.pushNavigate(destination, "To other sticky page"),
      liveView("sticky-nested-child", StickyNestedChildLiveView(), sticky = true)
    )

final class StickyNestedChildLiveView extends LiveView[StickyNestedChildLiveView.Msg.type, Int]:
  import StickyNestedChildLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg => ZIO.succeed(model + 1)

  def view(model: Signal[Int]) =
    sectionTag(
      idAttr := "sticky-nested-content",
      span(idAttr := "sticky-nested-counter", model.map(_.toString)),
      button(on.click(Msg), "Increment sticky child"),
      liveView("sticky-nested-grandchild", StickyNestedGrandchildLiveView())
    )

object StickyNestedChildLiveView:
  case object Msg

final class StickyNestedGrandchildLiveView
    extends LiveView[StickyNestedGrandchildLiveView.Msg.type, Int]:
  import StickyNestedGrandchildLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg => ZIO.succeed(model + 1)

  def view(model: Signal[Int]) =
    sectionTag(
      idAttr := "sticky-nested-grandchild-content",
      span(idAttr := "sticky-nested-grandchild-counter", model.map(_.toString)),
      button(on.click(Msg), "Increment sticky grandchild")
    )

object StickyNestedGrandchildLiveView:
  case object Msg

final class NavigationLiveView(
  page: String,
  mountSequence: AtomicInteger,
  a: LiveLocation,
  b: LiveLocation,
  c: LiveLocation,
  d: LiveLocation)
    extends LiveView[NavigationLiveView.Msg, Int]:
  import NavigationLiveView.*

  def mount(ctx: MountContext) =
    val sequence = mountSequence.incrementAndGet()
    ZIO.succeed(sequence)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.ToB =>
      ctx.flash.put(Notice, "Flash from A") *>
        ctx.nav.pushNavigate(b).as(model)
    case Msg.ToC =>
      ctx.flash.put(Notice, s"Flash from ${page.toUpperCase}") *>
        ctx.nav.pushNavigate(c).as(model)
    case Msg.ToD =>
      ctx.flash.put(Notice, "Flash from A") *>
        ctx.nav.pushNavigate(d).as(model)
    case Msg.RedirectC =>
      ctx.flash.put(Notice, "Flash from B") *>
        ctx.nav.redirect(c).as(model)
    case Msg.ToA =>
      ctx.flash.put(Notice, "Flash from C") *>
        ctx.nav.pushNavigate(a).as(model)

  override def view(model: Signal[Int]) =
    mainTag(
      idAttr := s"view-$page",
      h1(s"Navigation ${page.toUpperCase}"),
      span(idAttr := "mount-id", model.map(_.toString)),
      div(idAttr  := "flash", flash(Notice)(message => span(message))),
      page match
        case "a" =>
          div(
            button(on.click(Msg.ToB), "To B"),
            button(on.click(Msg.ToC), "To C"),
            button(on.click(Msg.ToD), "To D"),
            link.pushNavigate(b, "Link to B")
          )
        case "b" => button(on.click(Msg.RedirectC), "Redirect to C")
        case _   => button(on.click(Msg.ToA), "To A")
    )
end NavigationLiveView

object NavigationLiveView:
  private val Notice = FlashKind("notice")

  enum Msg:
    case ToB, ToC, ToD, RedirectC, ToA

final class NavigationGuardLiveView(patch: LiveLocation, target: LiveLocation)
    extends LiveView.Routed[NavigationGuardLiveView.Msg, NavigationGuardLiveView.Model, Option[
      String
    ]]:
  import NavigationGuardLiveView.*

  def mount(step: Option[String], ctx: MountContext) =
    ZIO.succeed(
      Model(
        note = "",
        step,
        connected = ctx.connection match
          case Connection.Connected(_) => true
          case Connection.Disconnected => false
      )
    )

  override def handleParams(
    model: Model,
    step: Option[String],
    url: URL,
    ctx: ParamsContext
  ) = ZIO.succeed(model.copy(step = step))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Change(note) => ZIO.succeed(model.copy(note = note))

  def view(model: Signal[Model]) =
    val note = model.map(_.note)

    mainTag(
      h1("Navigation guard"),
      link.pushPatch(patch, "Guarded patch"),
      link.pushNavigate(target, "Guarded navigation"),
      p(idAttr := "guard-connected", model.map(_.connected.toString)),
      p(idAttr := "guard-step", model.map(_.step.getOrElse("base"))),
      form(
        idAttr := "navigation-guard-form",
        navigation.guardWhen(model.map(_.note.nonEmpty), "Discard unsaved changes?"),
        on.change(params => Msg.Change(params.getOrElse("note", ""))),
        label(forId  := "guard-note", "Unsaved note"),
        input(idAttr := "guard-note", nameAttr := "note", value := note)
      )
    )
end NavigationGuardLiveView

object NavigationGuardLiveView:
  enum Msg:
    case Change(note: String)

  final case class Model(note: String, step: Option[String], connected: Boolean)

object NavigationGuardTargetLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.succeed(())

  def view(model: Signal[Unit]) = h1("Navigation guard target")

final class UpstreamNavigationLiveView(
  page: String,
  a: LiveLocation,
  b: LiveLocation,
  stream: LiveLocation)
    extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.succeed(())

  def view(model: Signal[Unit]) =
    mainTag(
      idAttr := s"upstream-$page",
      h1(s"Navigation $page"),
      link.pushNavigate(a, "LiveView A"),
      link.pushNavigate(b, "LiveView B"),
      link.pushNavigate(stream, "LiveView (other session)"),
      if page == "a" then link.pushPatchUnsafe("?patched=true", "Patch this LiveView")
      else span()
    )

final class Issue3686LiveView(
  page: String,
  a: LiveLocation,
  b: LiveLocation,
  c: LiveLocation)
    extends LiveView[Issue3686LiveView.Msg.type, Unit]:
  import Issue3686LiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(())

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg =>
      page match
        case "A" => ctx.flash.put(Info, "Flash from A") *> ctx.nav.pushNavigate(b)
        case "B" => ctx.flash.put(Info, "Flash from B") *> ctx.nav.redirect(c)
        case _   => ctx.flash.put(Info, "Flash from C") *> ctx.nav.pushNavigate(a)

  def view(model: Signal[Unit]) =
    val next = page match
      case "A" => "B"
      case "B" => "C"
      case _   => "A"
    mainTag(
      h1(page),
      button(on.click(Msg), s"To $next"),
      div(idAttr := "flash", "%{}", flash(Info)(message => span(message)))
    )

object Issue3686LiveView:
  private val Info = FlashKind("info")
  case object Msg

final class RedirectLoopLiveView
    extends LiveView.Routed.Eventless[RedirectLoopLiveView.Model, Boolean]:
  import RedirectLoopLiveView.*

  def mount(loop: Boolean, ctx: MountContext) =
    if loop then ZIO.succeed(Model(shouldLoop = false, message = "Too many redirects"))
    else ZIO.succeed(Model(shouldLoop = true, message = ""))

  override def handleParams(model: Model, loop: Boolean, url: URL, ctx: ParamsContext) =
    if loop && model.shouldLoop then ctx.nav.pushPatchUnsafe("?loop=true").as(model)
    else if loop then ZIO.succeed(model.copy(message = "Too many redirects"))
    else ZIO.succeed(model.copy(shouldLoop = true, message = ""))

  def view(model: Signal[Model]) =
    mainTag(
      div(idAttr := "message", model.map(_.message)),
      link.pushPatchUnsafe("?loop=true", "Redirect Loop")
    )

object RedirectLoopLiveView:
  final case class Model(shouldLoop: Boolean, message: String)

final class HostedUploadLiveView
    extends LiveView[HostedUploadLiveView.Msg, HostedUploadLiveView.Model]:
  import HostedUploadLiveView.*

  def mount(ctx: MountContext) =
    ctx.uploads.allow(Upload).map { upload =>
      Model(
        upload,
        connected = ctx.connection match
          case Connection.Connected(_) => true
          case Connection.Disconnected => false
      )
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate | Msg.Progress =>
      ctx.uploads.get(Upload).map {
        case Some(upload) => model.copy(upload = upload)
        case None         => model
      }
    case Msg.Save =>
      ctx.uploads
        .consumeCompleted(Upload) { entry =>
          val content = String(entry.result.toArray, StandardCharsets.UTF_8)
          ZIO.succeed(ConsumeDecision.Consume(UploadedFile(entry.client.fileName, content)))
        }.map { case (files, upload) =>
          model.copy(upload = upload, uploaded = model.uploaded ++ files)
        }

  def view(model: Signal[Model]) =
    val upload = model.map(_.upload)
    mainTag(
      h1("Hosted upload"),
      span(idAttr := "upload-connected", model.map(_.connected.toString)),
      form(
        idAttr := "hosted-upload-form",
        on.change(_ => Msg.Validate),
        on.submit(Msg.Save),
        liveFileInput(upload, upload.onProgress(_ => Msg.Progress)),
        button(typ := "submit", "Upload"),
        upload.map(_.entries).splitBy(_.ref) { (_, entry) =>
          articleTag(
            cls := "upload-entry",
            span(cls := "upload-name", entry.map(_.client.fileName)),
            progressTag(
              cls     := "upload-progress",
              value   := entry.map(_.progress.toString),
              maxAttr := "100",
              entry.map(current => s"${current.progress}%")
            ),
            uploadErrors(entry).splitBy(_.toString) { (_, error) =>
              p(cls := "upload-error", error.map(errorMessage))
            }
          )
        },
        uploadErrors(upload).splitBy(_.toString) { (_, error) =>
          p(cls := "upload-error", error.map(errorMessage))
        }
      ),
      model.map(_.uploaded).splitBy(_.name) { (_, file) =>
        sectionTag(
          cls := "uploaded-file",
          span(idAttr := "uploaded-name", file.map(_.name)),
          pre(idAttr  := "uploaded-content", file.map(_.content))
        )
      }
    )
  end view
end HostedUploadLiveView

object HostedUploadLiveView:
  private val Upload = LiveUploadDef.inMemory(
    name = "document",
    accept = LiveUploadAccept.only(".txt"),
    maxEntries = 1,
    maxFileSize = 1024L,
    chunkSize = 16
  )

  enum Msg:
    case Validate, Progress, Save

  final case class UploadedFile(name: String, content: String)

  final case class Model(
    upload: LiveUpload[Chunk[Byte]],
    connected: Boolean,
    uploaded: Vector[UploadedFile] = Vector.empty)

  private def errorMessage(error: LiveUploadError): String = error match
    case LiveUploadError.NotAccepted  => "Unacceptable file type"
    case LiveUploadError.TooLarge     => "File is too large"
    case LiveUploadError.TooManyFiles => "Too many files"
    case _                            => "Upload failed"
