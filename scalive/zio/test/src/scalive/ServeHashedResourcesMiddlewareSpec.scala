package scalive

import zio.*
import zio.http.*
import zio.test.*

object ServeHashedResourcesMiddlewareSpec extends ZIOSpecDefault:

  private val routes =
    Routes.empty @@ ServeHashedResourcesMiddleware(Path.empty / "static", "public")

  private def runRequest(path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => routes.runZIO(Request.get(url))

  override def spec = suite("ServeHashedResourcesMiddlewareSpec")(
    test("serves resource for hashed path") {
      val hashed = "/static/app-0123456789abcdef0123456789abcdef.js"
      for
        res  <- ZIO.scoped(runRequest(hashed))
        body <- res.body.asString
      yield assertTrue(res.status == Status.Ok, body.contains("test-asset"))
    },
    test("returns 404 for invalid hashed path") {
      val invalid = "/static/unknown-0123456789abcdef0123456789abcdef.js"
      for res <- ZIO.scoped(runRequest(invalid))
      yield assertTrue(res.status == Status.NotFound)
    }
  )
