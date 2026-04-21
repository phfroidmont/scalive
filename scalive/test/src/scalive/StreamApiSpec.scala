package scalive

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import scalive.socket.SocketStreamRuntime
import scalive.socket.StreamRuntimeState

object StreamApiSpec extends ZIOSpecDefault:

  final case class User(id: Int, name: String)

  private val usersDef = LiveStreamDef.byId[User, Int]("users")(_.id)

  override def spec = suite("StreamApiSpec")(
    test("stream encodes stream inserts") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        users <- runtime.stream(
                   usersDef,
                   List(User(1, "chris"), User(2, "callan")),
                   at = StreamAt.Last,
                   reset = false,
                   limit = None
                 )
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
        _ <- runtime.stream(
               usersDef,
               List(User(1, "chris"), User(2, "callan")),
               at = StreamAt.Last,
               reset = false,
               limit = None
             )
        _      <- SocketStreamRuntime.prune(streamRef)
        pruned <- runtime.get(usersDef).some
      yield assertTrue(pruned.entries.isEmpty)
    },
    test("stream encodes delete_by_dom_id patches") {
      for
        streamRef <- Ref.make(StreamRuntimeState.empty)
        runtime = new SocketStreamRuntime(streamRef)
        _ <- runtime.stream(
               usersDef,
               List(User(1, "chris"), User(2, "callan")),
               at = StreamAt.Last,
               reset = false,
               limit = None
             )
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
            ul(
              idAttr       := "users",
              phx.onUpdate := "stream",
              users.stream { (domId, user) =>
                li(idAttr := domId, user.name)
              }
            )
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
