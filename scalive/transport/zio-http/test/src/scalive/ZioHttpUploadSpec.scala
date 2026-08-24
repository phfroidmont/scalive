package scalive

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.protocol.phoenix.*
import scalive.runtime.contracts.*
import scalive.upload.*

object ZioHttpUploadSpec extends ZIOSpecDefault:
  private val transportConfig = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false
  ).toOption.get

  private val uploadEvent = BrowserToServerEvent[Json]("inspect-upload")

  private final case class TestSessionId(value: String) derives JsonCodec
  private final case class TestAuthClaims(sessionId: TestSessionId) derives JsonCodec
  private final case class TestCurrentUser(name: String)
  private final case class TestAuthState(
    active: Ref[Set[TestSessionId]],
    revalidations: Ref[Int],
    interrupted: Ref[Int],
    revalidationGate: Option[(Promise[Nothing, Unit], Promise[Nothing, Unit])] = None)

  private enum AdmissionMessage:
    case Completed

  private val admissionTask = AsyncKey[Unit]("admission-cleanup")

  private val authentication =
    LiveMountAspect.fromRequest[TestAuthState, Any, TestAuthClaims, TestCurrentUser](
      request =>
        for
          state <- ZIO.service[TestAuthState]
          id = TestSessionId(request.url.queryParam("session").getOrElse("missing"))
          valid <- state.active.get.map(_.contains(id))
          result <-
            if valid then ZIO.succeed(TestAuthClaims(id) -> TestCurrentUser(id.value))
            else ZIO.fail(Response.unauthorized)
        yield result,
      (claims, _) =>
        for
          state <- ZIO.service[TestAuthState]
          _     <- state.revalidations.update(_ + 1)
          _ <- ZIO.foreachDiscard(state.revalidationGate) { case (entered, release) =>
                 entered.succeed(()).unit *> release.await
               }
          valid <- state.active.get.map(_.contains(claims.sessionId))
          user <-
            if valid then ZIO.succeed(TestCurrentUser(claims.sessionId.value))
            else ZIO.fail(LiveMountFailure.unauthorized("revoked test session"))
        yield user
    )

  private final class AdmissionView(user: TestCurrentUser, state: TestAuthState)
      extends LiveView[AdmissionMessage, String]:
    def mount(ctx: MountContext) =
      ctx.connection match
        case Connection.Disconnected => ZIO.succeed(user.name)
        case Connection.Connected(connected) =>
          connected.async
            .start(admissionTask)(
              ZIO.never.ensuring(state.interrupted.update(_ + 1))
            )(_ => AdmissionMessage.Completed)
            .as(user.name)

    def handleMessage(model: String, ctx: MessageContext) = _ => ZIO.succeed(model)

    def view(model: Signal[String]) = div(idAttr := "authenticated", model)

  private val admittedApplication
    : LiveApplication[TestAuthState & LiveConnections[TestSessionId]] = scalive.Live.router(
    scalive.Live
      .session("authenticated")
      .withAdmission(authentication)(_.sessionId)(
        scalive.live.context((user: TestCurrentUser, state: TestAuthState) =>
          new AdmissionView(user, state)
        ),
        (scalive.live / "next").context((user: TestCurrentUser, state: TestAuthState) =>
          new AdmissionView(user, state)
        )
      )
  )

  private object FailingAdmissionView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext) = ctx.connection match
      case Connection.Disconnected => ZIO.unit
      case Connection.Connected(_)  => ZIO.fail(new Exception("connected startup failed"))

    def view(model: Signal[Unit]) = div()

  private val failingAdmittedApplication
    : LiveApplication[TestAuthState & LiveConnections[TestSessionId]] = scalive.Live.router(
    scalive.Live
      .session("failing-authenticated")
      .withAdmission(authentication)(_.sessionId)(scalive.live(FailingAdmissionView))
  )

  private final case class WriterState(name: String, bytes: Chunk[Byte])

  private final class RecordingWriter(
    initialized: Ref[Int],
    writes: Ref[Vector[Chunk[Byte]]],
    aborts: Ref[Vector[LiveUploadAbortReason]],
    entered: Queue[Unit],
    release: Promise[Nothing, Unit],
    blockWrites: Boolean)
      extends LiveUploadWriter[WriterState, Chunk[Byte]]:
    def init(client: UploadClientMetadata): Task[WriterState] =
      initialized.update(_ + 1).as(WriterState(client.fileName, Chunk.empty))

    def writeChunk(data: Chunk[Byte], state: WriterState): Task[WriterState] =
      writes.update(_ :+ data) *>
        ZIO.when(blockWrites)(entered.offer(()) *> release.await) *>
        ZIO.succeed(state.copy(bytes = state.bytes ++ data))

    def complete(state: WriterState): Task[Chunk[Byte]] = ZIO.succeed(state.bytes)

    def abort(state: WriterState, reason: LiveUploadAbortReason): Task[Unit] =
      aborts.update(_ :+ reason)

    def discard(result: Chunk[Byte]): Task[Unit] = ZIO.unit

  private final class ExternalUploader extends LiveUploadExternalUploader[String]:
    def preflight(client: UploadClientMetadata): Task[LiveExternalUploadResult[String]] =
      ZIO.succeed(
        LiveExternalUploadResult.Ready(
          ExternalUploadClientConfig(
            Json.Obj(
              "uploader" -> Json.Str("test"),
              "name"     -> Json.Str(client.fileName)
            )
          ),
          client.fileName
        )
      )

  private final case class TestState(
    hostedRef: UploadRef,
    externalRef: UploadRef,
    componentRef: UploadRef)

  private final case class Fixture(
    application: LiveApplication[Any],
    state: Queue[TestState],
    initialized: Ref[Int],
    writes: Ref[Vector[Chunk[Byte]]],
    aborts: Ref[Vector[LiveUploadAbortReason]],
    entered: Queue[Unit],
    release: Promise[Nothing, Unit],
    observedEventEntries: Ref[Vector[String]],
    componentProgress: Ref[Vector[Int]])

  private def fixture(blockWrites: Boolean = false): UIO[Fixture] =
    for
      state                <- Queue.unbounded[TestState]
      initialized          <- Ref.make(0)
      writes               <- Ref.make(Vector.empty[Chunk[Byte]])
      aborts               <- Ref.make(Vector.empty[LiveUploadAbortReason])
      entered              <- Queue.unbounded[Unit]
      release              <- Promise.make[Nothing, Unit]
      observedEventEntries <- Ref.make(Vector.empty[String])
      componentProgress    <- Ref.make(Vector.empty[Int])
      rootRefs             <- Ref.make(Option.empty[(UploadRef, UploadRef)])
      writer = RecordingWriter(initialized, writes, aborts, entered, release, blockWrites)
      hosted = LiveUploadDef.hosted(
                 "hosted",
                 LiveUploadAccept.only(".txt"),
                 writer,
                 maxEntries = 2,
                 maxFileSize = 5L,
                 chunkSize = 3,
                 chunkTimeout = 2.seconds
               )
      external = LiveUploadDef.external(
                   "external",
                   LiveUploadAccept.Any,
                   ExternalUploader(),
                   maxEntries = 1,
                   maxFileSize = 9L,
                   chunkSize = 4,
                   chunkTimeout = 3.seconds
                 )
      componentUpload = LiveUploadDef.external(
                          "component",
                          LiveUploadAccept.Any,
                          ExternalUploader(),
                          progress = Some(new LiveUploadProgress[String]:
                            def onProgress(entry: LiveUploadEntry[String]) =
                              componentProgress.update(_ :+ entry.progress)
                          )
                        )
      componentDefinition = new LiveComponent.Eventless[Unit, LiveUpload[String]]:
                              def mount(props: Unit, ctx: MountContext) =
                                ctx.uploads.allow(componentUpload).tap { upload =>
                                  rootRefs.get.flatMap {
                                    case Some((hostedRef, externalRef)) =>
                                      state.offer(TestState(hostedRef, externalRef, upload.ref)).unit
                                    case None => ZIO.dieMessage("root uploads were not mounted")
                                  }
                                }
                              def view(
                                props: Signal[Unit],
                                model: Signal[LiveUpload[String]],
                                self: ComponentRef[Nothing]
                              ) = div(idAttr := "component-upload")
      componentInstance = component(componentDefinition, "component-upload")
      view = new LiveView.Eventless[(LiveUpload[Chunk[Byte]], LiveUpload[String])]:
               override val hooks = LiveHooks
                 .empty[Nothing, (LiveUpload[Chunk[Byte]], LiveUpload[String])]
                 .onBrowserEvent(uploadEvent) { (model, _, ctx) =>
                   ctx.uploads.get(hosted).flatMap { current =>
                     observedEventEntries
                       .set(current.toVector.flatMap(_.entries.map(_.client.fileName)))
                       .as(model)
                   }
                 }
               def mount(ctx: MountContext) =
                 for
                   hostedUpload   <- ctx.uploads.allow(hosted)
                   externalUpload <- ctx.uploads.allow(external)
                   _              <- rootRefs.set(Some(hostedUpload.ref -> externalUpload.ref))
                 yield hostedUpload -> externalUpload
               def view(model: Signal[(LiveUpload[Chunk[Byte]], LiveUpload[String])]) =
                 mainTag(componentInstance.render(()))
      application = scalive.Live.router(scalive.live(view))
    yield Fixture(
      application,
      state,
      initialized,
      writes,
      aborts,
      entered,
      release,
      observedEventEntries,
      componentProgress
    )

  private final case class Bootstrap(
    rootId: String,
    session: String,
    static: String,
    csrf: String,
    cookie: Cookie.Request,
    url: String)

  private final case class SocketClient(
    channel: WebSocketChannel,
    incoming: Queue[PhoenixEnvelope],
    closed: Promise[Nothing, Unit],
    closeCode: Promise[Nothing, Int]):
    def send(envelope: PhoenixEnvelope): Task[Unit] =
      channel.send(ChannelEvent.read(WebSocketFrame.text(PhoenixEnvelope.encode(envelope))))

    def sendBinary(bytes: Chunk[Byte]): Task[Unit] =
      channel.send(ChannelEvent.read(WebSocketFrame.binary(bytes)))

    def receive: Task[PhoenixEnvelope] =
      incoming.take.timeoutFail(Exception("websocket reply timed out"))(5.seconds)

    def receiveReply(ref: String, topic: String): Task[PhoenixEnvelope] =
      receive.flatMap { envelope =>
        if envelope.ref == PhoenixRef.Value(ref) && envelope.topic == topic then ZIO.succeed(envelope)
        else receiveReply(ref, topic)
      }

    def close: UIO[Unit] =
      channel.send(ChannelEvent.read(WebSocketFrame.close(1000, None))).ignore

  private def withServer[R: EnvironmentTag: HasNoScope, A](
    application: LiveApplication[R]
  )(run: Int => ZIO[Client & Scope, Throwable, A]): ZIO[R, Throwable, A] =
    for
      started <- Promise.make[Nothing, Int]
      _ <- (Server
             .install(ZioHttp.routes(application, transportConfig))
             .tap(started.succeed)
             .zipRight(ZIO.never)
              .provideSomeLayer[R](Server.defaultWith(_.onAnyOpenPort)))
             .forkDaemon
      port <- started.await
      completed <- Promise.make[Nothing, Exit[Throwable, A]]
      _ <- (run(port).exit.flatMap(completed.succeed).zipRight(ZIO.never))
             .provideLayer(Scope.default ++ Client.default)
             .forkDaemon
      result <- completed.await.flatMap(ZIO.suspendSucceed(_))
    yield result

  private def bootstrap(port: Int, path: String = "/"): ZIO[Client, Throwable, Bootstrap] =
    for
      response <- Client.batched(Request.get(URL.decode(s"http://127.0.0.1:$port$path").toOption.get))
      body     <- response.body.asString
      rootId   <- requiredAttribute(body, "id")
      session  <- requiredAttribute(body, "data-phx-session")
      static   <- requiredAttribute(body, "data-phx-static")
      csrf     <- requiredAttribute(body, "content")
      cookie <- ZIO
                  .fromOption(
                    response.headers(Header.SetCookie).map(_.value)
                      .find(_.name == "_scalive_csrf")
                  ).orElseFail(AssertionError("missing CSRF cookie"))
    yield Bootstrap(rootId, session, static, csrf, Cookie.Request(cookie.name, cookie.content), path)

  private def connect(port: Int, bootstrap: Bootstrap): ZIO[Client & Scope, Throwable, SocketClient] =
    for
      incoming   <- Queue.unbounded[PhoenixEnvelope]
      registered <- Promise.make[Nothing, WebSocketChannel]
      closed     <- Promise.make[Nothing, Unit]
      closeCode  <- Promise.make[Nothing, Int]
      app = Handler.webSocket { channel =>
              channel.receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                  registered.succeed(channel).unit
                case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                  ZIO.fromEither(PhoenixEnvelope.decode(text).left.map(Exception(_))).flatMap(
                    incoming.offer
                  ).unit
                case ChannelEvent.Read(frame: WebSocketFrame.Close) =>
                  closeCode.succeed(frame.status).unit *> closed.succeed(()).unit
                case ChannelEvent.Unregistered =>
                  closed.succeed(()).unit
                case ChannelEvent.ExceptionCaught(cause) => ZIO.fail(cause)
                case _                                   => ZIO.unit
              }
            }
      url = URL.decode(
              s"ws://127.0.0.1:$port/live/websocket?_csrf_token=${bootstrap.csrf}"
            ).toOption.get
      _ <- ZIO
             .serviceWithZIO[Client](
               _.url(url)
                 .addHeader(
                   Header.Custom("cookie", s"${bootstrap.cookie.name}=${bootstrap.cookie.content}")
                 )
                 .socket(WebSocketApp(app.handler))
             ).forkScoped
      channel <- registered.await
    yield SocketClient(channel, incoming, closed, closeCode)

  private def joinRoot(socket: SocketClient, bootstrap: Bootstrap): Task[PhoenixEnvelope] =
    val topic   = s"lv:${bootstrap.rootId}"
    val joinRef = PhoenixRef.Value("root-join")
    socket.send(
      PhoenixEnvelope(
        joinRef,
        PhoenixRef.Value("1"),
        topic,
        "phx_join",
        Json.Obj(
          "url"      -> Json.Str(bootstrap.url),
          "redirect" -> Json.Null,
          "flash"    -> Json.Null,
          "session"  -> Json.Str(bootstrap.session),
          "static"   -> Json.Str(bootstrap.static),
          "params"   -> Json.Obj.empty,
          "sticky"   -> Json.Bool(false)
        )
      )
    ) *> socket.receiveReply("1", topic)

  private def redirectRoot(
    socket: SocketClient,
    bootstrap: Bootstrap,
    destination: String,
    ref: String
  ): Task[PhoenixEnvelope] =
    val topic = s"lv:${bootstrap.rootId}"
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value(s"redirect-$ref"),
        PhoenixRef.Value(ref),
        topic,
        "phx_join",
        Json.Obj(
          "url"      -> Json.Null,
          "redirect" -> Json.Str(destination),
          "flash"    -> Json.Null,
          "session"  -> Json.Str(bootstrap.session),
          "static"   -> Json.Str(bootstrap.static),
          "params"   -> Json.Obj("_mounts" -> Json.Num(1)),
          "sticky"   -> Json.Bool(false)
        )
      )
    ) *> socket.receiveReply(ref, topic)

  private def preflight(
    socket: SocketClient,
    topic: String,
    ref: String,
    uploadRef: UploadRef,
    entries: Vector[Json],
    cid: Option[Long] = None
  ): Task[PhoenixEnvelope] =
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value("root-join"),
        PhoenixRef.Value(ref),
        topic,
        "allow_upload",
        Json.Obj(
          "ref"     -> Json.Str(uploadRef.value),
          "entries" -> Json.Arr(entries*),
          "cid"     -> cid.fold[Json](Json.Null)(value => Json.Num(BigDecimal(value)))
        )
      )
    ) *> socket.receiveReply(ref, topic)

  private def entry(ref: String, name: String, size: Long): Json = Json.Obj(
    "ref"           -> Json.Str(ref),
    "name"          -> Json.Str(name),
    "relative_path" -> Json.Null,
    "size"          -> Json.Num(BigDecimal(size)),
    "type"          -> Json.Str("text/plain"),
    "last_modified" -> Json.Num(7),
    "meta"          -> Json.Obj("source" -> Json.Str("test"))
  )

  private def response(envelope: PhoenixEnvelope): Json.Obj = envelope.payload match
    case Json.Obj(fields) => fields.toMap.apply("response").asInstanceOf[Json.Obj]
    case other            => throw AssertionError(s"expected reply object, got $other")

  private def field(obj: Json.Obj, name: String): Json = obj.fields.toMap.apply(name)

  private def status(envelope: PhoenixEnvelope): String = envelope.payload match
    case Json.Obj(fields) => fields.toMap.apply("status").asString.get
    case other            => throw AssertionError(s"expected status reply, got $other")

  private def reason(envelope: PhoenixEnvelope): String = field(response(envelope), "reason").asString.get

  private def hostedToken(reply: PhoenixEnvelope, entryRef: String): String =
    val entries = field(response(reply), "entries").asInstanceOf[Json.Obj]
    field(entries, entryRef).asString.get

  private def uploadJoin(
    socket: SocketClient,
    joinRef: String,
    pushRef: String,
    topic: String,
    token: String
  ): Task[PhoenixEnvelope] =
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value(joinRef),
        PhoenixRef.Value(pushRef),
        topic,
        "phx_join",
        Json.Obj("token" -> Json.Str(token))
      )
    ) *> socket.receiveReply(pushRef, topic)

  private def binary(
    joinRef: String,
    ref: String,
    topic: String,
    bytes: Chunk[Byte]
  ): Chunk[Byte] =
    PhoenixUploadProtocol
      .encodeBinary(PhoenixUploadBinaryFrame(joinRef, ref, topic, "chunk", bytes)).toOption.get

  def spec = suite("ZIO HTTP upload transport")(
    test("unauthorized joins allocate no lifecycle and malformed frames close the socket") {
      val factories = AtomicInteger(0)
      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext): Task[Unit] = ZIO.unit
        def view(model: Signal[Unit]): HtmlElement[Nothing] = div()
      val application = scalive.Live.router(scalive.live {
        factories.incrementAndGet()
        View
      })

      withServer(application) { port =>
        for
          page   <- bootstrap(port)
          socket <- connect(port, page)
          before = factories.get()
          _ <- socket.send(
                 PhoenixEnvelope(
                   PhoenixRef.Value("unauthorized"),
                   PhoenixRef.Value("1"),
                   "lv:unknown",
                   "phx_join",
                   Json.Obj(
                     "session" -> Json.Str("invalid"),
                     "params"  -> Json.Obj.empty
                   )
                 )
               )
          rejected <- socket.receiveReply("1", "lv:unknown")
          afterRejected = factories.get()
          _ <- socket.send(
                 PhoenixEnvelope(
                   PhoenixRef.Null,
                   PhoenixRef.Value("2"),
                   "phoenix",
                   "heartbeat",
                   Json.Obj.empty
                 )
               )
          heartbeat <- socket.receiveReply("2", "phoenix")
          _ <- socket.channel.send(ChannelEvent.read(WebSocketFrame.text("{")))
          closed <- socket.closed.await.timeout(5.seconds)
          afterMalformed = factories.get()
        yield assertTrue(
          before == 1,
          status(rejected) == "error",
          afterRejected == 1,
          heartbeat.event == "phx_reply",
          closed.nonEmpty,
          afterMalformed == 1
        )
      }
    },
    test("revocation closes every matching transport and reconnect revalidates durable state") {
      val firstId = TestSessionId("first")
      val otherId = TestSessionId("other")
      for
        active        <- Ref.make(Set(firstId, otherId))
        revalidations <- Ref.make(0)
        interrupted   <- Ref.make(0)
        state          = TestAuthState(active, revalidations, interrupted)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        stateLayer      = ZLayer.succeed[TestAuthState](state)
        connectionsLayer = ZLayer.succeed[LiveConnections[TestSessionId]](connections)
        result <- withServer(admittedApplication) { port =>
                    for
                      firstPage <- bootstrap(port, "/?session=first")
                      otherPage <- bootstrap(port, "/?session=other")
                      firstTab  <- connect(port, firstPage)
                      secondTab <- connect(port, firstPage)
                      otherTab  <- connect(port, otherPage)
                      firstJoin <- joinRoot(firstTab, firstPage)
                      secondJoin <- joinRoot(secondTab, firstPage)
                      otherJoin <- joinRoot(otherTab, otherPage)
                      _ <- active.update(_ - firstId)
                      _ <- connections.disconnect(firstId)
                      _ <- firstTab.closed.await.timeoutFail(
                             Exception("first tab did not close")
                           )(5.seconds)
                      _ <- secondTab.closed.await.timeoutFail(
                             Exception("second tab did not close")
                           )(5.seconds)
                      firstCode  <- firstTab.closeCode.await.timeout(100.millis)
                      secondCode <- secondTab.closeCode.await.timeout(100.millis)
                      cleaned <- interrupted.get.repeatUntil(_ >= 2)
                      heartbeat = PhoenixEnvelope(
                                    PhoenixRef.Null,
                                    PhoenixRef.Value("other-heartbeat"),
                                    "phoenix",
                                    "heartbeat",
                                    Json.Obj.empty
                                  )
                      _          <- otherTab.send(heartbeat)
                      otherAlive <- otherTab.receiveReply("other-heartbeat", "phoenix")
                      staleTab   <- connect(port, firstPage)
                      staleJoin  <- joinRoot(staleTab, firstPage)
                      _          <- connections.disconnect(firstId)
                      _ <- staleTab.send(
                             PhoenixEnvelope(
                               PhoenixRef.Null,
                               PhoenixRef.Value("stale-heartbeat"),
                               "phoenix",
                               "heartbeat",
                               Json.Obj.empty
                             )
                           )
                      staleHeartbeat <- staleTab.receiveReply("stale-heartbeat", "phoenix")
                      rejectedPage <- Client.batched(
                                        Request.get(
                                          URL.decode(
                                            s"http://127.0.0.1:$port/?session=first"
                                          ).toOption.get
                                        )
                                      )
                      checks <- revalidations.get
                      _      <- staleTab.close
                      _      <- otherTab.close
                    yield assertTrue(
                      status(firstJoin) == "ok",
                      status(secondJoin) == "ok",
                      status(otherJoin) == "ok",
                      firstCode.forall(_ == 1001),
                      secondCode.forall(_ == 1001),
                      cleaned >= 2,
                      status(otherAlive) == "ok",
                      status(staleJoin) == "error",
                      reason(staleJoin) == "unauthorized",
                      status(staleHeartbeat) == "ok",
                      rejectedPage.status == Status.Unauthorized,
                      checks == 4
                    )
                  }.provideLayer(stateLayer ++ connectionsLayer)
      yield result
    },
    test("same-session navigation reuses its binding and failed revalidation closes the transport") {
      val sessionId = TestSessionId("navigation")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        interrupted   <- Ref.make(0)
        state          = TestAuthState(active, revalidations, interrupted)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        stateLayer      = ZLayer.succeed[TestAuthState](state)
        connectionsLayer = ZLayer.succeed[LiveConnections[TestSessionId]](connections)
        result <- withServer(admittedApplication) { port =>
                    for
                      page     <- bootstrap(port, "/?session=navigation")
                      socket   <- connect(port, page)
                      initial  <- joinRoot(socket, page)
                      navigated <- redirectRoot(socket, page, "/next", "2")
                      _        <- active.set(Set.empty)
                      _ <- socket.send(
                             PhoenixEnvelope(
                               PhoenixRef.Value("failed-navigation"),
                               PhoenixRef.Value("3"),
                               s"lv:${page.rootId}",
                               "phx_join",
                               Json.Obj(
                                 "url"      -> Json.Null,
                                 "redirect" -> Json.Str("/"),
                                 "flash"    -> Json.Null,
                                 "session"  -> Json.Str(page.session),
                                 "static"   -> Json.Str(page.static),
                                 "params"   -> Json.Obj("_mounts" -> Json.Num(2)),
                                 "sticky"   -> Json.Bool(false)
                               )
                             )
                           )
                      _ <- socket.closed.await.timeoutFail(
                             Exception("failed navigation did not close the transport")
                           )(5.seconds)
                      code <- socket.closeCode.await.timeout(100.millis)
                      checks <- revalidations.get
                      cleaned <- interrupted.get.repeatUntil(_ >= 2)
                    yield assertTrue(
                      status(initial) == "ok",
                      status(navigated) == "ok",
                      code.forall(_ == 1001),
                      checks == 3,
                      cleaned >= 2
                    )
                  }.provideLayer(stateLayer ++ connectionsLayer)
      yield result
    },
    test("disconnect during connected revalidation closes the pending transport") {
      val sessionId = TestSessionId("pending")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        interrupted   <- Ref.make(0)
        entered       <- Promise.make[Nothing, Unit]
        release       <- Promise.make[Nothing, Unit]
        state          = TestAuthState(active, revalidations, interrupted, Some(entered -> release))
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        stateLayer      = ZLayer.succeed[TestAuthState](state)
        connectionsLayer = ZLayer.succeed[LiveConnections[TestSessionId]](connections)
        result <- withServer(admittedApplication) { port =>
                    for
                      page   <- bootstrap(port, "/?session=pending")
                      socket <- connect(port, page)
                      joining <- joinRoot(socket, page).fork
                      _       <- entered.await
                      _       <- active.set(Set.empty)
                      _       <- connections.disconnect(sessionId)
                      _ <- socket.closed.await.timeoutFail(
                             Exception("pending transport did not close")
                           )(5.seconds)
                      code   <- socket.closeCode.await.timeout(100.millis)
                      _      <- release.succeed(())
                      _      <- joining.interrupt
                      checks <- revalidations.get
                    yield assertTrue(code.forall(_ == 1001), checks == 1)
                  }.provideLayer(stateLayer ++ connectionsLayer)
      yield result
    },
    test("connected startup failure rolls back pending admission") {
      val sessionId = TestSessionId("startup-failure")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        interrupted   <- Ref.make(0)
        state          = TestAuthState(active, revalidations, interrupted)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        stateLayer      = ZLayer.succeed[TestAuthState](state)
        connectionsLayer = ZLayer.succeed[LiveConnections[TestSessionId]](connections)
        result <- withServer(failingAdmittedApplication) { port =>
                    for
                      page     <- bootstrap(port, "/?session=startup-failure")
                      socket   <- connect(port, page)
                      rejected <- joinRoot(socket, page)
                      _        <- connections.disconnect(sessionId)
                      _ <- socket.send(
                             PhoenixEnvelope(
                               PhoenixRef.Null,
                               PhoenixRef.Value("after-failure"),
                               "phoenix",
                               "heartbeat",
                               Json.Obj.empty
                             )
                           )
                      heartbeat <- socket.receiveReply("after-failure", "phoenix")
                      _         <- socket.close
                    yield assertTrue(status(rejected) == "error", status(heartbeat) == "ok")
                  }.provideLayer(stateLayer ++ connectionsLayer)
      yield result
    },
    test("root preflight projects canonical hosted and external responses with exact claims") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      joined <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      topic   = s"lv:${page.rootId}"
                      hosted <- preflight(
                                  socket,
                                  topic,
                                  "2",
                                  refs.hostedRef,
                                  Vector(
                                    entry("valid", "valid.txt", 5L),
                                    entry("large", "large.txt", 6L),
                                    entry("wrong", "wrong.png", 1L),
                                    entry("extra", "extra.txt", 1L),
                                    entry("overflow", "overflow.txt", 1L)
                                  )
                                )
                      external <- preflight(
                                    socket,
                                    topic,
                                    "3",
                                    refs.externalRef,
                                    Vector(entry("external", "direct.bin", 4L))
                                  )
                      token  = hostedToken(hosted, "valid")
                      claims <- ZioHttpSecurity
                                  .verifyUploadCredential(transportConfig, token)
                                  .mapError(error => Exception(error.toString))
                      initialized <- fixture.initialized.get
                      _           <- socket.close
                    yield
                      val hostedResponse   = response(hosted)
                      val hostedConfig     = field(hostedResponse, "config").asInstanceOf[Json.Obj]
                      val hostedErrors     = field(hostedResponse, "errors").asInstanceOf[Json.Obj]
                      val externalResponse = response(external)
                      val externalEntries  = field(externalResponse, "entries").asInstanceOf[Json.Obj]
                      assertTrue(
                        status(joined) == "ok",
                        field(hostedResponse, "ref") == Json.Str(refs.hostedRef.value),
                        hostedConfig == Json.Obj(
                          "max_file_size" -> Json.Num(5),
                          "max_entries"   -> Json.Num(2),
                          "chunk_size"    -> Json.Num(3),
                          "chunk_timeout" -> Json.Num(2000)
                        ),
                        field(hostedErrors, "large") == Json.Arr(Json.Str("too_large")),
                        field(hostedErrors, "wrong") == Json.Arr(Json.Str("not_accepted")),
                        field(hostedErrors, "overflow") == Json.Arr(Json.Str("too_many_files")),
                        field(externalEntries, "external") == Json.Obj(
                          "uploader" -> Json.Str("test"),
                          "name"     -> Json.Str("direct.bin")
                        ),
                        claims.lifecycleId.value > 0L,
                        claims.epoch.value > 0L,
                        claims.componentInstanceId.isEmpty,
                        claims.uploadRef == refs.hostedRef,
                        claims.entryRef == UploadEntryRef("valid"),
                        claims.registrationGeneration == 1L,
                        claims.expectedTopic == "lvu:valid",
                        initialized == 0
                      )
                  }
      yield result
    },
    test("hosted joins reject every stale binding before duplicate initialization") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      _      <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      rootTopic = s"lv:${page.rootId}"
                      hosted <- preflight(
                                  socket,
                                  rootTopic,
                                  "2",
                                  refs.hostedRef,
                                  Vector(entry("entry", "file.txt", 5L))
                                )
                      token  = hostedToken(hosted, "entry")
                      claims <- ZioHttpSecurity
                                  .verifyUploadCredential(transportConfig, token)
                                  .mapError(error => Exception(error.toString))
                      tampered = token.updated(token.length - 1, if token.last == 'A' then 'B' else 'A')
                      wrongTopic <- uploadJoin(socket, "u0", "3", "lvu:other", token)
                      wrongEntryToken <- ZioHttpSecurity.issueUploadCredential(
                                           transportConfig,
                                           claims.copy(
                                             entryRef = UploadEntryRef("other"),
                                             expectedTopic = "lvu:other"
                                           )
                                         )
                      wrongEntry <- uploadJoin(socket, "u1", "4", "lvu:entry", wrongEntryToken)
                      wrongOwnerToken <- ZioHttpSecurity.issueUploadCredential(
                                           transportConfig,
                                           claims.copy(componentInstanceId = Some(ComponentInstanceId(999L)))
                                         )
                      wrongOwner <- uploadJoin(socket, "u2", "5", "lvu:entry", wrongOwnerToken)
                      staleLifecycleToken <- ZioHttpSecurity.issueUploadCredential(
                                               transportConfig,
                                               claims.copy(lifecycleId = LifecycleId(claims.lifecycleId.value + 1L))
                                             )
                      staleLifecycle <- uploadJoin(socket, "u3", "6", "lvu:entry", staleLifecycleToken)
                      staleEpochToken <- ZioHttpSecurity.issueUploadCredential(
                                           transportConfig,
                                           claims.copy(epoch = Epoch(claims.epoch.value + 1L))
                                         )
                      staleEpoch <- uploadJoin(socket, "u4", "7", "lvu:entry", staleEpochToken)
                      staleGenerationToken <- ZioHttpSecurity.issueUploadCredential(
                                                transportConfig,
                                                claims.copy(
                                                  registrationGeneration =
                                                    claims.registrationGeneration + 1L
                                                )
                                              )
                      staleGeneration <- uploadJoin(
                                           socket,
                                           "u5",
                                           "8",
                                           "lvu:entry",
                                           staleGenerationToken
                                         )
                      unknownClaims = claims.copy(
                                        lifecycleId = LifecycleId(claims.lifecycleId.value + 99L),
                                        entryRef = UploadEntryRef("unknown"),
                                        expectedTopic = "lvu:unknown"
                                      )
                      unknownToken <- ZioHttpSecurity.issueUploadCredential(
                                        transportConfig,
                                        unknownClaims
                                      )
                      unknown   <- uploadJoin(socket, "u6", "9", "lvu:unknown", unknownToken)
                      invalid   <- uploadJoin(socket, "u7", "10", "lvu:entry", tampered)
                      valid     <- uploadJoin(socket, "upload-join", "11", "lvu:entry", token)
                      duplicate <- uploadJoin(socket, "duplicate", "12", "lvu:entry", token)
                      initialized <- fixture.initialized.get
                      _           <- socket.close
                    yield assertTrue(
                      status(wrongTopic) == "error" && reason(wrongTopic) == "invalid_token",
                      status(wrongEntry) == "error" && reason(wrongEntry) == "invalid_token",
                      status(wrongOwner) == "error" && reason(wrongOwner) == "disallowed",
                      status(staleLifecycle) == "error" && reason(staleLifecycle) == "disallowed",
                      status(staleEpoch) == "error" && reason(staleEpoch) == "disallowed",
                      status(staleGeneration) == "error" && reason(staleGeneration) == "disallowed",
                      status(unknown) == "error" && reason(unknown) == "disallowed",
                      status(invalid) == "error" && reason(invalid) == "invalid_token",
                      status(valid) == "ok",
                      status(duplicate) == "error" && reason(duplicate) == "already_registered",
                      initialized == 1
                    )
                  }
      yield result
    },
    test("binary routing is registration exact and limit failures preserve the root lifecycle") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      _      <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      rootTopic = s"lv:${page.rootId}"
                      hosted <- preflight(
                                  socket,
                                  rootTopic,
                                  "2",
                                  refs.hostedRef,
                                  Vector(
                                    entry("valid", "valid.txt", 5L),
                                    entry("limited", "limited.txt", 5L)
                                  )
                                )
                      validToken   = hostedToken(hosted, "valid")
                      limitedToken = hostedToken(hosted, "limited")
                      _ <- uploadJoin(socket, "valid-join", "3", "lvu:valid", validToken)
                      _ <- uploadJoin(socket, "limit-join", "4", "lvu:limited", limitedToken)
                      wrongJoinSend <- socket.sendBinary(
                                         binary(
                                           "wrong-join",
                                           "5",
                                           "lvu:valid",
                                           Chunk.single(1.toByte)
                                         )
                                       ).fork
                      wrongJoin <- socket.receiveReply("5", "lvu:valid")
                      _         <- wrongJoinSend.join
                      wrongTopicSend <- socket.sendBinary(
                                          binary(
                                            "valid-join",
                                            "6",
                                            "lvu:unknown",
                                            Chunk.single(1.toByte)
                                          )
                                        ).fork
                      wrongTopic <- socket.receiveReply("6", "lvu:unknown")
                      _          <- wrongTopicSend.join
                      validSend <- socket.sendBinary(
                                     binary(
                                       "valid-join",
                                       "7",
                                       "lvu:valid",
                                       Chunk[Byte](1, 2, 3)
                                     )
                                   ).fork
                      valid <- socket.receiveReply("7", "lvu:valid")
                      _     <- validSend.join
                      declaredSend <- socket.sendBinary(
                                        binary(
                                          "valid-join",
                                          "8",
                                          "lvu:valid",
                                          Chunk[Byte](4, 5, 6)
                                        )
                                      ).fork
                      declared <- socket.receiveReply("8", "lvu:valid")
                      _        <- declaredSend.join
                      tooLargeSend <- socket.sendBinary(
                                        binary(
                                          "limit-join",
                                          "9",
                                          "lvu:limited",
                                          Chunk.fromArray(Array.fill(1_000_001)(1.toByte))
                                        )
                                      ).fork
                      tooLarge <- socket.receiveReply("9", "lvu:limited")
                      _        <- tooLargeSend.join
                      heartbeat = PhoenixEnvelope(
                                    PhoenixRef.Null,
                                    PhoenixRef.Value("10"),
                                    "phoenix",
                                    "heartbeat",
                                    Json.Obj.empty
                                  )
                      _              <- socket.send(heartbeat)
                      heartbeatReply <- socket.receiveReply("10", "phoenix")
                      writes         <- fixture.writes.get
                      _              <- socket.close
                    yield assertTrue(
                      reason(wrongJoin) == "disallowed",
                      reason(wrongTopic) == "disallowed",
                      status(valid) == "ok",
                      status(declared) == "error",
                      reason(declared) == "file_size_limit_exceeded",
                      field(response(declared), "limit") == Json.Num(5),
                      status(tooLarge) == "error",
                      reason(tooLarge) == "file_size_limit_exceeded",
                      field(response(tooLarge), "limit") == Json.Num(3),
                      heartbeatReply.topic == "phoenix",
                      heartbeatReply.event == "phx_reply",
                      status(heartbeatReply) == "ok",
                      writes == Vector(Chunk[Byte](1, 2, 3))
                    )
                  }
      yield result
    },
    test("fragmented binary upload messages are assembled before routing") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      _      <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      rootTopic = s"lv:${page.rootId}"
                      hosted <- preflight(
                                  socket,
                                  rootTopic,
                                  "2",
                                  refs.hostedRef,
                                  Vector(entry("fragmented", "fragmented.txt", 3L))
                                )
                      token = hostedToken(hosted, "fragmented")
                      _     <- uploadJoin(socket, "fragmented-join", "3", "lvu:fragmented", token)
                      frame = binary(
                                "fragmented-join",
                                "4",
                                "lvu:fragmented",
                                Chunk[Byte](1, 2, 3)
                              )
                      splitAt = frame.length - 2
                      _ <- socket.channel.send(
                             ChannelEvent.read(WebSocketFrame.Binary(frame.take(splitAt), false))
                           )
                      _ <- socket.channel.send(
                             ChannelEvent.read(WebSocketFrame.Continuation(frame.drop(splitAt), true))
                           )
                      reply  <- socket.receiveReply("4", "lvu:fragmented")
                      writes <- fixture.writes.get
                      _      <- socket.close
                    yield assertTrue(
                      status(reply) == "ok",
                      writes == Vector(Chunk[Byte](1, 2, 3))
                    )
                  }
      yield result
    },
    test("progress and ordinary event metadata require the current owner and synchronize first") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      _      <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      topic   = s"lv:${page.rootId}"
                      component <- preflight(
                                    socket,
                                    topic,
                                    "2",
                                    refs.componentRef,
                                    Vector(entry("component-entry", "component.bin", 8L)),
                                    cid = Some(1L)
                                  )
                      exact <- sendProgress(
                                socket,
                                topic,
                                "3",
                                refs.componentRef.value,
                                "component-entry",
                                60,
                                Some(1L)
                              )
                      eventReply <- sendEventWithUploads(
                                     socket,
                                     topic,
                                     "4",
                                     refs.hostedRef,
                                     Vector(entry("event-entry", "event.txt", 2L))
                                   )
                      staleRef <- sendProgress(
                                   socket,
                                   topic,
                                   "5",
                                   refs.hostedRef.value,
                                   "component-entry",
                                   40,
                                   Some(1L)
                                 )
                      staleOwner <- sendProgress(
                                      socket,
                                      topic,
                                      "6",
                                      refs.componentRef.value,
                                      "component-entry",
                                      50,
                                      None
                                    )
                      progress <- fixture.componentProgress.get
                      observed <- fixture.observedEventEntries.get
                      _        <- socket.close
                    yield assertTrue(
                      status(component) == "ok",
                      status(staleRef) == "error",
                      status(staleOwner) == "error",
                      status(exact) == "ok",
                      progress == Vector(60),
                      status(eventReply) == "ok",
                      observed == Vector("event.txt")
                    )
                  }
      yield result
    },
    test("component destruction acknowledgements delete only marked retained CIDs") {
      val hideEvent = BrowserToServerEvent[Json]("hide-component")
      val definition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(idAttr := "destroyed-component")
      val instance = component(definition, "destroyed-component")
      val view = new LiveView[Nothing, Boolean]:
        override val hooks = LiveHooks.empty[Nothing, Boolean]
          .onBrowserEvent(hideEvent)((_, _, _) => ZIO.succeed(false))
        def mount(ctx: MountContext) = ZIO.succeed(true)
        def handleMessage(model: Boolean, ctx: MessageContext): Nothing => Task[Boolean] = identity
        def view(model: Signal[Boolean]) = mainTag(model.when(div(instance.render(()))))

      withServer(scalive.Live.router(scalive.live(view))) { port =>
        for
          page   <- bootstrap(port)
          socket <- connect(port, page)
          joined <- joinRoot(socket, page)
          topic = s"lv:${page.rootId}"
          _ <- sendComponentCids(socket, topic, "2", "cids_will_destroy", Vector(1))
          active <- sendComponentCids(socket, topic, "3", "cids_destroyed", Vector(1))
          _ <- socket.send(
                 PhoenixEnvelope(
                   PhoenixRef.Value("root-join"),
                   PhoenixRef.Value("4"),
                   topic,
                   "event",
                   Json.Obj(
                     "type"  -> Json.Str("click"),
                     "event" -> Json.Str(hideEvent.value),
                     "value" -> Json.Obj.empty,
                     "cid"   -> Json.Null
                   )
                 )
               )
          removed  <- socket.receiveReply("4", topic)
          marked   <- sendComponentCids(socket, topic, "5", "cids_will_destroy", Vector(1))
          destroyed <- sendComponentCids(socket, topic, "6", "cids_destroyed", Vector(1))
          repeated <- sendComponentCids(socket, topic, "7", "cids_destroyed", Vector(1))
          _        <- socket.close
        yield assertTrue(
          status(joined) == "ok",
          field(response(active), "cids") == Json.Arr(),
          status(removed) == "ok",
          status(marked) == "ok",
          field(response(destroyed), "cids") == Json.Arr(Json.Num(1)),
          field(response(repeated), "cids") == Json.Arr()
        )
      }
    },
    test("socket shutdown retires an admitted hosted writer") {
      for
        fixture <- fixture()
        result <- withServer(fixture.application) { port =>
                    for
                      page   <- bootstrap(port)
                      socket <- connect(port, page)
                      _      <- joinRoot(socket, page)
                      refs <- (fixture.state.take *> fixture.state.take)
                                .timeoutFail(Exception("upload refs timed out"))(5.seconds)
                      topic   = s"lv:${page.rootId}"
                      hosted <- preflight(
                                  socket,
                                  topic,
                                  "2",
                                  refs.hostedRef,
                                  Vector(entry("active", "active.txt", 5L))
                                )
                      token = hostedToken(hosted, "active")
                      _     <- uploadJoin(socket, "active-join", "3", "lvu:active", token)
                      _     <- socket.close
                      aborts <- fixture.aborts.get.repeatUntil(_.nonEmpty)
                    yield assertTrue(aborts == Vector(LiveUploadAbortReason.SocketShutdown))
                  }
      yield result
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def sendProgress(
    socket: SocketClient,
    topic: String,
    ref: String,
    uploadRef: String,
    entryRef: String,
    progress: Int,
    cid: Option[Long]
  ): Task[PhoenixEnvelope] =
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value("root-join"),
        PhoenixRef.Value(ref),
        topic,
        "progress",
        Json.Obj(
          "ref"       -> Json.Str(uploadRef),
          "entry_ref" -> Json.Str(entryRef),
          "progress"  -> Json.Num(BigDecimal(progress)),
          "cid"       -> cid.fold[Json](Json.Null)(value => Json.Num(BigDecimal(value)))
        )
      )
    ) *> socket.receiveReply(ref, topic)

  private def sendEventWithUploads(
    socket: SocketClient,
    topic: String,
    ref: String,
    uploadRef: UploadRef,
    entries: Vector[Json]
  ): Task[PhoenixEnvelope] =
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value("root-join"),
        PhoenixRef.Value(ref),
        topic,
        "event",
        Json.Obj(
          "type"  -> Json.Str("click"),
          "event" -> Json.Str(uploadEvent.value),
          "value" -> Json.Obj.empty,
          "uploads" -> Json.Obj(
            uploadRef.value -> Json.Arr(entries*)
          ),
          "cid" -> Json.Null
        )
      )
    ) *> socket.receiveReply(ref, topic)

  private def sendComponentCids(
    socket: SocketClient,
    topic: String,
    ref: String,
    event: String,
    cids: Vector[Int]
  ): Task[PhoenixEnvelope] =
    socket.send(
      PhoenixEnvelope(
        PhoenixRef.Value("root-join"),
        PhoenixRef.Value(ref),
        topic,
        event,
        Json.Obj("cids" -> Json.Arr(cids.map(Json.Num(_))*))
      )
    ) *> socket.receiveReply(ref, topic)

  private def requiredAttribute(html: String, name: String): IO[AssertionError, String] =
    val pattern = (java.util.regex.Pattern.quote(name) + "=\"([^\"]+)\"").r
    ZIO.fromOption(pattern.findFirstMatchIn(html).map(_.group(1))).orElseFail(
      AssertionError(s"missing $name")
    )
end ZioHttpUploadSpec
