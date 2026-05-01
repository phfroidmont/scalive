package scalive

import zio.*
import zio.json.ast.Json

private[scalive] trait ClientEventRuntime:
  def push(name: String, payload: Json): UIO[Unit]

private[scalive] object ClientEventRuntime:
  val Disabled: ClientEventRuntime = new ClientEventRuntime:
    def push(name: String, payload: Json): UIO[Unit] = ZIO.unit
