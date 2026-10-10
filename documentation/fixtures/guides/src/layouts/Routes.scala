package layouts

import zio.http.codec.PathCodec

import scalive.*

object Routes:
  val home     = live
  val projects = live / "projects"
  val project  = live / "projects" / PathCodec.string("project")
