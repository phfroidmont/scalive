package scalive

import zio.*
import zio.http.*
import zio.http.codec.*
import zio.test.*

object LiveLocationSpec extends ZIOSpecDefault:
  private val usersPath = PathCodec.empty / "users" / PathCodec.string("id")

  final case class UserLocation(id: String, tab: Option[String])

  override def spec = suite("LiveLocationSpec")(
    test("encodes path and query values") {
      val encoded = LiveParamsCodec.Encoded(
        pathParams = "a b",
        queryParams = QueryParams("tab" -> "settings & profile")
      )
      val href    = LiveLocation.encode(usersPath, encoded).toOption.get.href
      val decoded = URL.decode(href).toOption.get

      assertTrue(
        href.startsWith("/users/a%20b?"),
        decoded.queryParam("tab").contains("settings & profile")
      )
    },
    test("round trips combined path and optional query params") {
      val codec = LiveParamsCodec.fromQuery[String, Option[String]](
        HttpCodec.query[String]("tab").optional
      )
      val url = URL.decode("/users/alice?tab=settings").toOption.get

      for decoded <- codec.decode("alice", url)
      yield assertTrue(
        decoded == ("alice", Some("settings")),
        codec
          .encode(decoded).exists(value =>
            value.pathParams == "alice" && value.queryParams.getAll("tab") == Chunk("settings")
          )
      )
    },
    test("returns path encode errors") {
      val positiveId = PathCodec
        .int("id")
        .transformOrFailRight(identity)(id => Either.cond(id > 0, id, "id must be positive"))

      assertTrue(
        LiveLocation
          .encode(positiveId, LiveParamsCodec.Encoded(-1, QueryParams.empty))
          .left
          .exists(_ == LiveLocation.EncodeError.Path("id must be positive"))
      )
    },
    test("route builders preserve query encode errors through checked and direct APIs") {
      val failingQuery = HttpCodec
        .query[Int]("page")
        .transformOrFailRight(identity)(_ => Left("page cannot be encoded"))
        .asQuery
      val route = (scalive.live / "search").query(failingQuery)

      val checkedError = route.locationEither(1).left.toOption
      val directError = scala.util
        .Try(route.location(1))
        .failed
        .toOption
        .collect { case error: LiveLocation.EncodingException => error.error }

      def isQueryError(error: LiveLocation.EncodeError): Boolean = error match
        case LiveLocation.EncodeError.Query(_) => true
        case _                                 => false

      assertTrue(
        checkedError.exists(isQueryError),
        directError.exists(isQueryError)
      )
    },
    test("adds encoded fragments with checked and direct APIs") {
      val location = LiveLocation
        .encode(usersPath, LiveParamsCodec.Encoded("alice", QueryParams.empty))
        .toOption
        .get

      assertTrue(
        location.withFragment("profile%20details").href == "/users/alice#profile%20details",
        location.withFragmentEither("%").isLeft,
        scala.util
          .Try(location.withFragment("%")).failed.toOption.exists(
            _.isInstanceOf[LiveLocation.EncodingException]
          )
      )
    },
    test("builds a mapped location from the same route declaration") {
      val userRoute =
        (scalive.live / "users" / PathCodec.string("id"))
          .queryOptional[String]("tab")
          .mapParams { case (id, tab) => UserLocation(id, tab) }(location =>
            location.id -> location.tab
          )

      assertTrue(
        userRoute.location(UserLocation("alice", Some("settings"))).href ==
          "/users/alice?tab=settings",
        userRoute.location(UserLocation("alice", None)).href == "/users/alice",
        userRoute.locationEither(UserLocation("alice", None)).isRight
      )
    },
    test("builds Unit locations without an argument") {
      val home = scalive.live / "home"

      assertTrue(home.location.href == "/home", home.locationEither.isRight)
    },
    test("creates an HTTP see-other response") {
      val response = (scalive.live / "home").location.seeOther

      assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode == "/home")
      )
    },
    test("builds required query locations") {
      val search = (scalive.live / "search").query[Int]("page")

      assertTrue(search.location(2).href == "/search?page=2")
    },
    test("direct location wraps checked path failures") {
      val positiveId = PathCodec
        .int("id")
        .transformOrFailRight(identity)(id => Either.cond(id > 0, id, "id must be positive"))
      val route = scalive.live / "users" / positiveId

      assertTrue(
        route.locationEither(-1).isLeft,
        scala.util
          .Try(route.location(-1)).failed.toOption.exists(
            _.isInstanceOf[LiveLocation.EncodingException]
          )
      )
    }
  )
end LiveLocationSpec
