package scalive

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.socket.SocketStreamRuntime
import scalive.socket.StreamRuntimeState
import scalive.socket.ComponentRuntimeState
import scalive.socket.SocketComponentRuntime

object StreamApiSpec extends ZIOSpecDefault:

  final case class User(id: Int, name: String)

  private val usersDef = LiveStreamDef.byId[User, Int]("users")(_.id)

  override def spec = suite("StreamApiSpec")(
    test("stream encodes stream inserts") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        users <- runtime.create(usersDef, List(User(1, "chris"), User(2, "callan")))
        rendered <- diffFor(users)
      yield
        val streamPayload = extractStreamPayload(rendered)
        assertTrue(
          users.entries.map(_.domId) == Vector("users-1", "users-2"),
          streamPayload.ref == "0",
          streamPayload.inserts == Vector("users-1", "users-2"),
          streamPayload.deleteIds.isEmpty,
          !streamPayload.reset
        )
    },
    test("prune clears pending inserts") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _ <- runtime.create(usersDef, List(User(1, "chris"), User(2, "callan")))
        _      <- SocketStreamRuntime.prune(streamRef)
        pruned <- runtime.get(usersDef).some
      yield assertTrue(pruned.entries.isEmpty)
    },
    test("stream encodes delete_by_dom_id patches") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _ <- runtime.create(usersDef, List(User(1, "chris"), User(2, "callan")))
        _        <- SocketStreamRuntime.prune(streamRef)
        deleted  <- runtime.deleteByDomId(usersDef, "users-1")
        rendered <- diffFor(deleted)
      yield
        val streamPayload = extractStreamPayload(rendered)
        assertTrue(
          deleted.entries.isEmpty,
          streamPayload.ref == "0",
          streamPayload.inserts.isEmpty,
          streamPayload.deleteIds == Vector("users-1"),
          !streamPayload.reset
        )
    },
    test("stream reset encodes reset patch and replaces the snapshot") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _ <- runtime.create(
               usersDef,
               List(User(1, "chris"), User(2, "callan"), User(3, "jose"))
             )
        _ <- SocketStreamRuntime.prune(streamRef)
        reset <- runtime.reset(
                   usersDef,
                   List(User(1, "chris"), User(3, "jose")),
                   StreamAt.Last
                 )
        rendered <- diffFor(reset)
      yield
        val streamPayload = extractStreamPayload(rendered)
        assertTrue(
          reset.snapshotEntries.map(_.domId) == Vector("users-1", "users-3"),
          streamPayload.ref == "0",
          streamPayload.inserts == Vector("users-1", "users-3"),
          streamPayload.deleteIds.isEmpty,
          streamPayload.reset
        )
    },
    test("create rejects an existing stream") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _         <- runtime.create(usersDef, List(User(1, "chris")))
        duplicate <- runtime.create(usersDef, List(User(2, "callan"))).exit
      yield assertTrue(duplicate.isFailure)
    },
    test("insertAll matches repeated insertion at the same index") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _ <- runtime.create(usersDef, List(User(1, "chris"), User(2, "callan")))
        _ <- SocketStreamRuntime.prune(streamRef)
        users <- runtime.insertAll(
                   usersDef,
                   List(User(3, "jose"), User(4, "mona")),
                   StreamAt.First
                 )
      yield assertTrue(users.snapshotEntries.map(_.value.id) == Vector(4, 3, 1, 2))
    },
    test("definition limit applies to every stream operation") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        definition = usersDef.keepLast(2)
        created <- runtime.create(
                     definition,
                     List(User(1, "chris"), User(2, "callan"), User(3, "jose"))
                   )
        _        <- SocketStreamRuntime.prune(streamRef)
        inserted <- runtime.insert(definition, User(4, "mona"), StreamAt.Last, updateOnly = false)
        reset <- runtime.reset(
                   definition,
                   List(User(1, "chris"), User(2, "callan"), User(3, "jose")),
                   StreamAt.Last
                 )
      yield assertTrue(
        created.snapshotEntries.map(_.value.id) == Vector(2, 3),
        inserted.snapshotEntries.map(_.value.id) == Vector(3, 4),
        reset.snapshotEntries.map(_.value.id) == Vector(2, 3)
      )
    },
    test("renderIn owns container and row stream attributes") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        users <- runtime.create(usersDef, List(User(1, "chris")))
      yield
        val html = HtmlBuilder.build(
          users.renderIn(ul, idAttr := "wrong", phx.update := PhxUpdate.Ignore) { user =>
            li(idAttr := "wrong-row", user.name)
          }
        )
        assertTrue(
          html.contains("<ul id=\"users\" phx-update=\"stream\">"),
          html.contains("<li id=\"users-1\">chris</li>"),
          !html.contains("wrong")
        )
    },
    test("stream snapshots register nested LiveViews only once") {
      val child = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
        def render(model: Unit) = div("child")

      for
        streamRef     <- Ref.make(StreamRuntimeState.empty)
        runtime       = new SocketStreamRuntime(streamRef)
        users         <- runtime.create(usersDef, List(User(1, "chris")))
        registrations <- Ref.make(0)
        nestedRuntime = new NestedLiveViewRuntime:
                          def register[Msg, Model](spec: NestedLiveViewSpec[Msg, Model]) =
                            registrations.update(_ + 1).as(
                              NestedLiveViewRegistration(
                                id = spec.id,
                                parentTopic = "lv:parent",
                                parentDomId = "parent",
                                topic = s"lv:${spec.id}",
                                session = "token",
                                sticky = false
                              )
                            )
        componentsRef <- Ref.make(ComponentRuntimeState.empty)
        _ <- SocketComponentRuntime.renderRoot(
               users.renderIn(ul)(user => div(liveView(s"user-${user.id}", child))),
               componentsRef,
               LiveContext(staticChanged = false, nestedLiveViews = nestedRuntime)
             )
        count <- registrations.get
      yield assertTrue(count == 1)
    }
  )

  final private case class StreamPayload(
    ref: String,
    inserts: Vector[String],
    deleteIds: Vector[String],
    reset: Boolean)

  private def diffFor(users: LiveStream[User]): Task[Json] =
    ZIO
      .fromEither(
        TreeDiff
          .initial(
            users.renderIn(ul)(user => li(user.name))
          ).toJsonAST
      )
      .mapError(error => new IllegalArgumentException(error))

  private def extractStreamPayload(diff: Json): StreamPayload =
    val root = diff match
      case obj: Json.Obj => obj
      case other         => throw new IllegalArgumentException(s"Expected root object, got $other")

    val comprehension = findStreamContainer(root).getOrElse(
      throw new IllegalArgumentException("Missing stream payload")
    )

    val stream = comprehension.fields
      .collectFirst { case ("stream", value: Json.Arr) => value }
      .getOrElse(
        throw new IllegalArgumentException("Missing stream payload")
      )

    val values = stream.elements
    val ref    =
      values.headOption
        .collect { case Json.Str(value) => value }.getOrElse(
          throw new IllegalArgumentException("Missing stream ref")
        )

    val inserts =
      values
        .lift(1)
        .collect { case Json.Arr(entries) =>
          entries.collect { case Json.Arr(insert) =>
            insert.headOption
              .collect { case Json.Str(domId) => domId }.getOrElse(
                throw new IllegalArgumentException("Invalid stream insert entry")
              )
          }.toVector
        }
        .getOrElse(Vector.empty)

    val deleteIds =
      values
        .lift(2)
        .collect { case Json.Arr(ids) =>
          ids.collect { case Json.Str(id) => id }.toVector
        }
        .getOrElse(Vector.empty)

    val reset = values.lift(3).contains(Json.Bool(true))

    StreamPayload(ref = ref, inserts = inserts, deleteIds = deleteIds, reset = reset)
  end extractStreamPayload

  private def findStreamContainer(json: Json): Option[Json.Obj] =
    json match
      case obj: Json.Obj =>
        if obj.fields.exists(_._1 == "stream") then Some(obj)
        else
          obj.fields.iterator
            .map(_._2)
            .collectFirst(Function.unlift(findStreamContainer))
      case Json.Arr(values) =>
        values.iterator.collectFirst(Function.unlift(findStreamContainer))
      case _                =>
        None
end StreamApiSpec
