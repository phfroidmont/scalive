package scalive

import zio.*
import zio.json.ast.Json

trait ClientEventRuntime:
  def push(name: String, payload: Json): UIO[Unit]

object ClientEventRuntime:
  val Disabled: ClientEventRuntime = new ClientEventRuntime:
    def push(name: String, payload: Json): UIO[Unit] = ZIO.unit
