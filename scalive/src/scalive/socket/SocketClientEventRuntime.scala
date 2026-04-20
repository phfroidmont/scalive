package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*

final private[scalive] class SocketClientEventRuntime(
  clientEventsRef: Ref[Vector[Diff.Event]])
    extends ClientEventRuntime:

  def push(name: String, payload: Json): UIO[Unit] =
    clientEventsRef.update(_ :+ Diff.Event(name, payload))

object SocketClientEventRuntime:
  def drain(clientEventsRef: Ref[Vector[Diff.Event]]): UIO[Vector[Diff.Event]] =
    clientEventsRef.modify(events => (events, Vector.empty))
