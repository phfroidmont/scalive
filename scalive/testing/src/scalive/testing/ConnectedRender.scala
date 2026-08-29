package scalive.testing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import zio.*
import zio.http.*
import zio.json.ast.Json

import scalive.*
import scalive.protocol.phoenix.*
import scalive.render.{BindingId, RenderDelta}
import scalive.runtime.connection.*
import scalive.runtime.contracts.*
import scalive.runtime.kernel.{NavigationKind, NavigationOutput}

/** Joins LiveViews through production route admission and connection supervision without starting a
  * network server.
  */
object ConnectedRender:
  private val DefaultConfig = ZioHttpConfig(
    "scalive-testing-signing-secret-0000000000000000",
    Duration.ofMinutes(30),
    secureCookie = false
  ).fold(error => throw IllegalStateException(error.toString), identity)

  /** Finalizes and joins one LiveView at the root route.
    *
    * The returned view preserves the LiveView's message type for [[ConnectedView.send]].
    */
  def join[Msg, Model](liveView: LiveView[Msg, Model]): RIO[Scope, ConnectedView[Msg]] =
    join(
      Live.router(scalive.live(liveView)),
      DefaultConfig,
      Request.get(URL.root)
    ).map(_.asInstanceOf[ConnectedView[Msg]])

  /** Executes the disconnected route, validates its bootstrap credentials, and starts its connected
    * lifecycle using the production connection supervisor.
    */
  def join[R](
    application: LiveApplication[R],
    config: ZioHttpConfig,
    request: Request,
    connectParams: Map[String, Json] = Map.empty
  ): ZIO[R & Scope, Throwable, ConnectedView[Nothing]] =
    for
      catalog   <- ZIO.attempt(ZioHttp.validate(application))
      routes    <- ZIO.attempt(ZioHttp.routes(application, config))
      page      <- DisconnectedRender.run(routes, request)
      bootstrap <- bootstrap(page)
      join = RootJoin(
               url = Some(request.url.encode),
               redirect = None,
               flash = None,
               session = bootstrap.session,
               static = Some(bootstrap.static),
               params = connectParams,
               sticky = false
             )
      admitted <- ZioHttpAdmission
                    .admit(
                      catalog,
                      config,
                      Some(bootstrap.csrfCookie),
                      Some(bootstrap.csrfToken),
                      rootExists = false,
                      bootstrap.topic,
                      join
                    ).mapError(Exception(_))
      session <- ConnectedSession.make(config)
      view    <- session.joinRoot(admitted, request, connectParams, bootstrap.csrfToken)
    yield view.asInstanceOf[ConnectedView[Nothing]]

  private def bootstrap(page: RenderedPage): Task[Bootstrap] = ZIO.attempt {
    val document = Jsoup.parse(page.html)
    val roots    = document
      .select("[data-phx-main][data-phx-session][data-phx-static]").asScala.toVector
    val root   = exactlyOne(roots, "LiveView root")
    val rootId = requiredAttribute(root, "id")
    val csrf   = Option(document.selectFirst("meta[name=csrf-token]"))
      .map(requiredAttribute(_, "content"))
      .getOrElse(throw IllegalArgumentException("Missing CSRF meta tag."))
    val csrfCookie = page.response
      .headers(Header.SetCookie).map(_.value)
      .find(_.name == "_scalive_csrf")
      .map(_.content)
      .getOrElse(throw IllegalArgumentException("Missing CSRF response cookie."))
    Bootstrap(
      topic = s"lv:$rootId",
      session = requiredAttribute(root, "data-phx-session"),
      static = requiredAttribute(root, "data-phx-static"),
      csrfToken = csrf,
      csrfCookie = csrfCookie
    )
  }

  private def requiredAttribute(element: Element, name: String): String =
    Option
      .when(element.hasAttr(name))(element.attr(name)).filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Missing $name attribute."))

  private def exactlyOne(elements: Vector[Element], description: String): Element =
    elements match
      case Vector(element) => element
      case _               =>
        throw IllegalArgumentException(s"Expected one $description, found ${elements.size}.")

  final private case class Bootstrap(
    topic: String,
    session: String,
    static: String,
    csrfToken: String,
    csrfCookie: String)
end ConnectedRender

/** A semantic handle to one connected root or nested LiveView.
  *
  * Actions resolve bindings from the latest committed HTML and wait for the correlated lifecycle
  * reply. Runtime connection objects and protocol frames remain private implementation details.
  */
final class ConnectedView[-Msg] private[testing] (
  session: ConnectedSession,
  state: ConnectedViewState):

  /** Stable protocol topic text for diagnostics. It is not a runtime handle. */
  val topic: String = state.topic.value

  /** Returns the latest committed semantic HTML projection. */
  def html: UIO[String] = state.html

  /** Returns the text of exactly one element matching `selector`. */
  def text(selector: String): Task[String] =
    html.flatMap(value => ZIO.attempt(ConnectedDom.selectOne(value, selector).text()))

  /** Dispatches the `phx-click` binding on exactly one matching element. */
  def click(selector: String): Task[Unit] =
    dispatchClick(ConnectedDom.selectOne(_, selector))

  /** Dispatches the `phx-click` binding on exactly one button with the given text. */
  def clickButton(label: String): Task[Unit] =
    dispatchClick(ConnectedDom.buttonByLabel(_, label))

  /** Dispatches the selected form's `phx-change` binding. */
  def changeForm(
    selector: String,
    fields: Vector[(String, String)],
    target: Option[String] = None
  ): Task[Unit] =
    dispatchForm(selector, "phx-change", fields, target)

  /** Dispatches the selected form's `phx-submit` binding. */
  def submitForm(selector: String, fields: Vector[(String, String)]): Task[Unit] =
    dispatchForm(selector, "phx-submit", fields, None)

  /** Sends one typed server message and waits for its committed output. */
  def send(message: Msg): Task[Unit] = state.send(message)

  /** Waits for the next uncorrelated async, subscription, or component output. */
  def awaitDiff: Task[Unit] = state.awaitDiff

  /** Joins a nested LiveView registered in this view's latest committed HTML. */
  def joinNested(instanceId: String): RIO[Scope, ConnectedView[Nothing]] =
    session.joinNested(state, instanceId)

  /** Runs hosted upload preflight and streams one complete entry to the lifecycle. */
  def upload(
    uploadRef: String,
    entryRef: String,
    fileName: String,
    mediaType: String,
    bytes: Chunk[Byte]
  ): Task[Unit] =
    state.upload(uploadRef, entryRef, fileName, mediaType, bytes)

  /** Reports whether this exact topic is still installed in the production supervisor. */
  def isJoined: UIO[Boolean] = session.isJoined(state.topic)

  /** Leaves this lifecycle through the production supervisor. */
  def leave: UIO[Unit] = session.leave(state.topic)

  private def dispatchClick(findElement: String => Element): Task[Unit] =
    for
      current <- html
      event   <- ZIO.attempt {
                 val element = findElement(current)
                 val binding = ConnectedDom.requiredBinding(element, "phx-click")
                 val cid     = ConnectedDom.componentCid(element)
                 RootEvent(
                   eventType = "click",
                   event = binding,
                   value = ConnectedDom.clickValue(element),
                   cid = cid
                 )
               }
      _ <- state.dispatch(event)
    yield ()

  private def dispatchForm(
    selector: String,
    attribute: String,
    fields: Vector[(String, String)],
    target: Option[String]
  ): Task[Unit] =
    for
      current <- html
      event   <- ZIO.attempt {
                 val element = ConnectedDom.selectOne(current, selector)
                 val binding = ConnectedDom.requiredBinding(element, attribute)
                 val cid     = ConnectedDom.componentCid(element)
                 val meta    = target.map(value => Json.Obj("_target" -> Json.Str(value)))
                 RootEvent(
                   eventType = "form",
                   event = binding,
                   value = Json.Str(ConnectedDom.urlEncoded(fields)),
                   cid = cid,
                   meta = meta
                 )
               }
      _ <- state.dispatch(event)
    yield ()
end ConnectedView

private object ConnectedDom:
  def selectOne(html: String, selector: String): Element =
    exactlyOne(
      Jsoup.parseBodyFragment(html).select(selector).asScala.toVector,
      s"'$selector' element"
    )

  def buttonByLabel(html: String, label: String): Element =
    val buttons = Jsoup
      .parseBodyFragment(html)
      .select("button").asScala.toVector.filter(_.text() == label)
    exactlyOne(buttons, s"button labelled '$label'")

  def requiredBinding(element: Element, attribute: String): String =
    Option
      .when(element.hasAttr(attribute))(element.attr(attribute)).filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Element has no $attribute binding."))

  def clickValue(element: Element): Json.Obj =
    val values = element.attributes().asList().asScala.toVector.collect {
      case attribute if attribute.getKey.startsWith("phx-value-") =>
        attribute.getKey.stripPrefix("phx-value-") -> Json.Str(attribute.getValue)
    } ++ Option.when(element.hasAttr("value"))("value" -> Json.Str(element.attr("value")))
    Json.Obj(values*)

  def componentCid(element: Element): Option[Long] =
    Option.when(element.hasAttr("phx-target"))(element.attr("phx-target").toLong).orElse {
      @annotation.tailrec
      def enclosing(current: Element): Option[Long] =
        if current == null then None
        else if current.hasAttr("data-phx-component") then
          Some(current.attr("data-phx-component").toLong)
        else enclosing(current.parent())

      enclosing(element)
    }

  def urlEncoded(fields: Vector[(String, String)]): String =
    fields.map { case (name, value) => s"${encode(name)}=${encode(value)}" }.mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def exactlyOne(elements: Vector[Element], description: String): Element =
    elements match
      case Vector(element) => element
      case _               =>
        throw IllegalArgumentException(s"Expected one $description, found ${elements.size}.")
end ConnectedDom

final private class ConnectedSession(
  config: ZioHttpConfig,
  supervisor: ConnectionSupervisor):

  def joinRoot[R](
    admitted: ZioHttpAdmission.Admitted[R],
    request: Request,
    connectParams: Map[String, Json],
    csrfToken: String
  ): ZIO[R & Scope, Throwable, ConnectedView[Nothing]] =
    for
      lifecycle <- admitted.route.prepareConnected(
                     admitted.url,
                     ZioHttp.connectedRequest(request, admitted.url),
                     admitted.claims
                   )
      state <- ConnectedViewState.make(
                 NestedTopic(s"lv:${admitted.claims.rootId}"),
                 admitted.url,
                 Some(csrfToken),
                 ZIO.suspendSucceed(supervisor.close)
               )
      metadata = RootConnectionMetadata(
                   staticChanged = ZioHttp.staticChanged(
                     ZioHttp.clientTrackedStatics(connectParams),
                     admitted.claims.trackedStatics,
                     admitted.url
                   ),
                   connectParams = connectParams,
                   initialFlash = admitted.claims.initialFlash.view
                     .map((key, value) => FlashKind(key) -> value).toMap
                 )
      connection <- supervisor
                      .startRootLifecycle(
                        lifecycle,
                        metadata,
                        admitted.claims.rootId,
                        state.topic,
                        loading = false,
                        state.sink,
                        requestedLifecycle = Some(LifecycleId(admitted.claims.lifecycle)),
                        bootstrapChildLifecycles = admitted.claims.nestedLifecycles.view
                          .mapValues(LifecycleId(_)).toMap
                      ).mapError(error => Exception(error.toString))
      _ <- state.install(connection)
      _ <- state.awaitJoined
    yield new ConnectedView(this, state)

  def joinNested(
    parent: ConnectedViewState,
    instanceId: String
  ): RIO[Scope, ConnectedView[Nothing]] =
    for
      parentHtml <- parent.html
      element    <- ZIO.attempt {
                   val value = Jsoup.parseBodyFragment(parentHtml).getElementById(instanceId)
                   if value == null then
                     throw new java.util.NoSuchElementException(
                       s"No nested LiveView with id '$instanceId'."
                     )
                   else value
                 }
      sessionToken <- ZIO.attempt(ConnectedDom.requiredBinding(element, "data-phx-session"))
      topic = NestedTopic(s"lv:$instanceId")
      join  = RootJoin(
               url = None,
               redirect = None,
               flash = None,
               session = sessionToken,
               static = Option
                 .when(element.hasAttr("data-phx-static"))(
                   element.attr("data-phx-static")
                 ).filter(_.nonEmpty),
               params = Map.empty,
               sticky = element.hasAttr("data-phx-sticky")
             )
      claims <- ZioHttp
                  .verifyNestedAdmission(config, topic.value, join).mapError(Exception(_))
      reservation <- supervisor.reserveNested(claims).mapError(error => Exception(error.toString))
      inherited   <- parent.currentUrl.get
      state       <- ConnectedViewState.make(
                 topic,
                 inherited,
                 csrfToken = None,
                 ZIO.suspendSucceed(supervisor.close)
               )
      metadata = RootConnectionMetadata(
                   staticChanged = false,
                   connectParams = Map.empty,
                   initialFlash = Map.empty
                 )
      connection <- supervisor
                      .startNested(
                        reservation,
                        inherited,
                        metadata,
                        reservation.registration.applicationId,
                        loading = element.hasClass("phx-loading"),
                        state.sink,
                        reattach = join.sticky,
                        requestedLifecycle = claims.childLifecycle
                      ).mapError(error => Exception(error.toString))
      _ <- state.install(connection)
      _ <- state.awaitJoined
    yield new ConnectedView(this, state)

  def isJoined(topic: NestedTopic): UIO[Boolean] =
    supervisor.lifecycleForTopic(topic).map(_.nonEmpty)

  def leave(topic: NestedTopic): UIO[Unit] =
    supervisor.routeLeave(topic).unit
end ConnectedSession

private object ConnectedSession:
  def make(config: ZioHttpConfig): RIO[Scope, ConnectedSession] =
    ConnectionSupervisor
      .make(
        ZioHttp.connectionConfig,
        new NestedCredentialIssuer:
          def issue(claims: NestedCredentialClaims) = ZioHttpSecurity.issueNested(config, claims)
        ,
        applicationId => NestedTopic(s"lv:$applicationId")
      ).map(new ConnectedSession(config, _))

final private class ConnectedViewState(
  val topic: NestedTopic,
  val currentUrl: Ref[URL],
  projection: Ref[Option[PhoenixRenderedState]],
  gate: Semaphore,
  connection: Promise[Nothing, ConnectedLifecycle],
  joined: Promise[Throwable, Unit],
  pending: Ref[Map[CommandId, Promise[Throwable, Unit]]],
  diffs: Queue[Unit],
  csrfToken: Option[String],
  disconnect: UIO[Unit]):

  val sink: ConnectionOutput => Task[Unit] = output =>
    gate.withPermit {
      val process = output match
        case value: ConnectionOutput.Joined =>
          update(value.delta) *> joined.succeed(()).unit
        case value: ConnectionOutput.JoinedNavigation =>
          update(value.delta) *> acknowledge(value.navigation) *> joined.succeed(()).unit
        case value: ConnectionOutput.Reply =>
          update(value.delta) *> complete(value.command)
        case value: ConnectionOutput.ReplyWithPayload =>
          update(value.delta) *> complete(value.command)
        case value: ConnectionOutput.UploadReply =>
          update(value.delta) *> complete(value.command)
        case value: ConnectionOutput.ReplyNavigation =>
          update(value.delta) *> complete(value.command) *> acknowledge(value.navigation)
        case value: ConnectionOutput.ReplyNavigationWithPayload =>
          update(value.delta) *> complete(value.command) *> acknowledge(value.navigation)
        case value: ConnectionOutput.Diff =>
          update(value.delta) *> diffs.offer(()).unit
        case value: ConnectionOutput.DiffNavigation =>
          update(value.delta) *> acknowledge(value.navigation) *> diffs.offer(()).unit
        case value: ConnectionOutput.ReplyDisconnect =>
          complete(value.command) *> disconnect.forkDaemon.unit
        case _: ConnectionOutput.Disconnect                => disconnect.forkDaemon.unit
        case ConnectionOutput.Rejected(command, rejection) =>
          fail(command, Exception(rejection.toString))
      process.tapError(failAll)
    }

  def install(value: ConnectedLifecycle): UIO[Unit] = connection.succeed(value).unit

  def awaitJoined: Task[Unit] =
    joined.await.timeoutFail(Exception("Timed out waiting for connected mount."))(5.seconds)

  def html: UIO[String] = gate.withPermit {
    projection.get.map(
      _.flatMap(state => PhoenixRenderedEncoder.html(state).toOption)
        .getOrElse(throw IllegalStateException("Connected HTML is unavailable."))
    )
  }

  def dispatch(event: RootEvent): Task[Unit] =
    ZIO.fromEither(event.toBindingPayload.left.map(Exception(_))).flatMap { payload =>
      correlated { (command, lifecycle) =>
        event.cid match
          case None =>
            lifecycle.browserEvent(
              command,
              BindingId.fromEncoded(event.event),
              payload,
              Some(event.toLiveEvent)
            )
          case Some(cid) =>
            resolveComponent(lifecycle, cid).flatMap {
              case Some(component) =>
                lifecycle.componentEvent(
                  command,
                  component,
                  BindingId.fromEncoded(event.event),
                  payload,
                  event.toLiveEvent
                )
              case None =>
                ZIO.fail(ConnectionError.SinkFailed(Exception(s"Unknown component target $cid.")))
            }
      }
    }

  def send(message: Any): Task[Unit] =
    correlated((command, lifecycle) => lifecycle.message(command, message))

  def awaitDiff: Task[Unit] =
    diffs.take.timeoutFail(Exception("Timed out waiting for connected output."))(5.seconds)

  def upload(
    uploadRef: String,
    entryRef: String,
    fileName: String,
    mediaType: String,
    bytes: Chunk[Byte]
  ): Task[Unit] =
    for
      lifecycle <- connection.await
      metadata = new UploadClientMetadata(
                   fileName,
                   relativePath = None,
                   sizeBytes = bytes.length.toLong,
                   mediaType = mediaType,
                   lastModifiedMillis = None,
                   metadata = None
                 )
      selectedRef: UploadEntryRef = UploadEntryRef(entryRef).asInstanceOf[UploadEntryRef]
      selected                    = Vector(selectedRef -> metadata)
      result <- correlatedResult((command, current) =>
                  current.preflightUpload(command, None, UploadRef(uploadRef), selected)
                )
      preflight <- ZIO.fromEither(result.left.map(error => Exception(error.toString)))
      entry     <- ZIO
                 .fromOption(preflight.entries.find(_.ref.value == entryRef))
                 .orElseFail(Exception(s"Upload preflight omitted entry '$entryRef'."))
      token <- ZIO
                 .fromOption(entry.hosted)
                 .orElseFail(Exception("The connected test harness supports hosted uploads only."))
      admitted <- lifecycle.admitUpload(
                    None,
                    UploadRef(uploadRef),
                    UploadEntryRef(entryRef),
                    token.upload.generation
                  )
      worker <- ZIO.fromEither(admitted.left.map(error => Exception(error.toString)))
      _      <- lifecycle.uploadChunk(worker, bytes).mapError(error => Exception(error.toString))
    yield ()

  private def correlated(
    offer: (CommandId, ConnectedLifecycle) => IO[ConnectionError, Unit]
  ): Task[Unit] =
    correlatedResult(offer)

  private def correlatedResult[A](
    offer: (CommandId, ConnectedLifecycle) => IO[ConnectionError, A]
  ): Task[A] =
    for
      command   <- ZIO.fromEither(CommandId.fresh()).mapError(error => Exception(error.toString))
      response  <- Promise.make[Throwable, Unit]
      _         <- pending.update(_.updated(command, response))
      lifecycle <- connection.await
      result    <- offer(command, lifecycle).tapError(error =>
                  pending.update(_ - command) *> response.fail(error)
                )
      _ <- response.await
             .timeoutFail(Exception("Timed out waiting for connected reply."))(5.seconds)
             .onInterrupt(pending.update(_ - command))
    yield result

  private def resolveComponent(
    lifecycle: ConnectedLifecycle,
    cid: Long
  ): IO[ConnectionError, Option[ComponentInstanceId]] =
    ZioHttp.resolveComponentCid(projection, gate, cid, lifecycle.componentForToken)

  private def update(delta: RenderDelta): Task[Unit] =
    projection.modify { previous =>
      val result = previous match
        case None =>
          delta match
            case RenderDelta.Replace(tree) => PhoenixRenderedEncoder.initial(tree, csrfToken)
            case _ => Left(IllegalStateException("Initial connected output was not a replacement."))
        case Some(current) => PhoenixRenderedEncoder.update(current, delta)

      result match
        case Right((next, _))       => Right(())                       -> Some(next)
        case Left(error: Throwable) => Left(error)                     -> previous
        case Left(error)            => Left(Exception(error.toString)) -> previous
    }.absolve

  private def acknowledge(navigation: NavigationOutput): Task[Unit] =
    if navigation.kind.isPatch then
      currentUrl.set(navigation.destination) *>
        connection.await.flatMap(_.internalPatch(navigation.destination))
    else if navigation.kind == NavigationKind.Redirect then disconnect.forkDaemon.unit
    else ZIO.unit

  private def complete(command: CommandId): UIO[Unit] =
    pending.modify(current => current.get(command) -> (current - command)).flatMap {
      case Some(response) => response.succeed(()).unit
      case None           => ZIO.unit
    }

  private def fail(command: CommandId, error: Throwable): UIO[Unit] =
    pending.modify(current => current.get(command) -> (current - command)).flatMap {
      case Some(response) => response.fail(error).unit
      case None           => ZIO.unit
    }

  private def failAll(error: Throwable): UIO[Unit] =
    pending
      .getAndSet(Map.empty).flatMap(values =>
        ZIO.foreachDiscard(values.values)(_.fail(error).unit)
      ) *> joined.fail(error).unit
end ConnectedViewState

private object ConnectedViewState:
  def make(
    topic: NestedTopic,
    initialUrl: URL,
    csrfToken: Option[String],
    disconnect: UIO[Unit]
  ): RIO[Scope, ConnectedViewState] =
    for
      currentUrl <- Ref.make(initialUrl)
      projection <- Ref.make(Option.empty[PhoenixRenderedState])
      gate       <- Semaphore.make(1L)
      connection <- Promise.make[Nothing, ConnectedLifecycle]
      joined     <- Promise.make[Throwable, Unit]
      pending    <- Ref.make(Map.empty[CommandId, Promise[Throwable, Unit]])
      diffs      <- Queue.sliding[Unit](64)
      _          <- ZIO.addFinalizer(diffs.shutdown)
    yield new ConnectedViewState(
      topic,
      currentUrl,
      projection,
      gate,
      connection,
      joined,
      pending,
      diffs,
      csrfToken,
      disconnect
    )
