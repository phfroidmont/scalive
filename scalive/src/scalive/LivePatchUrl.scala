package scalive

import zio.http.URL

private[scalive] object LivePatchUrl:
  def resolve(raw: String, current: URL): Either[String, URL] =
    if raw.isEmpty then Right(current)
    else
      val candidate =
        if raw.startsWith("?") then s"${current.path.encode}$raw"
        else raw

      URL.decode(candidate).left.map(_.toString)
