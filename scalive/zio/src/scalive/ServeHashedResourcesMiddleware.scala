package scalive

import zio.*
import zio.http.*

object ServeHashedResourcesMiddleware:

  private val hashedStaticRegex = """/static/(.+)-([a-f0-9]{32})(\.[^./]+)?""".r

  def apply(path: Path, resourcePrefix: String = "public"): Middleware[Any] =
    Middleware.interceptIncomingHandler(
      Handler.fromFunction[Request] { req =>
        val updated = req.url.path.encode match
          case hashedStaticRegex(base, _, ext) =>
            val targetPath =
              path.addLeadingSlash ++ Path.decode(s"/$base${Option(ext).getOrElse("")}")
            req.copy(url = req.url.copy(path = targetPath))
          case _ => req
        (updated, ())
      }
    ) @@ Middleware.serveResources(path, resourcePrefix)
