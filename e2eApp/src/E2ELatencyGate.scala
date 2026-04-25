import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

import zio.*

object E2ELatencyGate:
  private val semaphores = ConcurrentHashMap[String, Semaphore]()

  def await(event: String): UIO[Unit] =
    ZIO.attemptBlocking(semaphore(event).acquire()).orDie

  def releaseFromCode(code: String): UIO[Unit] =
    val event =
      if code.contains("validate") then Some("validate")
      else if code.contains("save") then Some("save")
      else None

    ZIO.succeed(event.foreach(semaphore(_).release()))

  private def semaphore(event: String): Semaphore =
    semaphores.computeIfAbsent(event, _ => Semaphore(0))
