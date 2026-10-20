package scalive

import java.io.IOException
import java.nio.channels.ClosedChannelException

import zio.*
import zio.test.*

import scalive.ZioHttp.TransportTermination
import scalive.runtime.connection.SerialWriter
import scalive.runtime.contracts.ConnectionId

object TransportTerminationSpec extends ZIOSpecDefault:
  private val connection = ConnectionId(7L)

  private def termination(
    writer: SerialWriter[Int],
    socketClosed: Promise[Nothing, Unit]
  ): UIO[TransportTermination] =
    TransportTermination.make(connection, writer, socketClosed.succeed(()).unit)

  private def warnings(logs: Chunk[ZTestLogger.LogEntry]) =
    logs.filter(_.logLevel.ordinal >= LogLevel.Warning.ordinal)

  private def drainLogs(logs: Chunk[ZTestLogger.LogEntry]) =
    logs.filter(entry =>
      entry.message() == "WebSocket writer drain failed" &&
        entry.annotations.get("connection_id").contains("7")
    )

  // A server-started disconnect drains the writer while the browser may already be closing.
  private def disconnectWhileWriting(
    failure: Throwable
  ): UIO[(Boolean, Chunk[ZTestLogger.LogEntry])] =
    ZIO.scoped {
      for
        entered      <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        writer       <- SerialWriter.make[Int](1)(_ =>
                          entered.succeed(()).unit *> release.await *> ZIO.fail(failure)
                        )
        socketClosed <- Promise.make[Nothing, Unit]
        terminating  <- termination(writer, socketClosed)
        _            <- writer.offer(1) *> entered.await
        _            <- terminating.disconnect
        _            <- release.succeed(())
        _            <- terminating.await
        closed       <- socketClosed.isDone
        logs         <- ZTestLogger.logOutput
      yield closed -> logs
    }.orDie

  def spec = suite("TransportTermination")(
    test("a disconnect overtaken by the browser closing the socket logs no warning") {
      for
        scope        <- Scope.make
        entered      <- Promise.make[Nothing, Unit]
        writer       <- scope.extend(
                          SerialWriter.make[Int](1)(_ => entered.succeed(()).unit *> ZIO.never)
                        )
        socketClosed <- Promise.make[Nothing, Unit]
        terminating  <- termination(writer, socketClosed)
        _            <- writer.offer(1) *> entered.await
        _            <- terminating.disconnect
        // The browser's close ends the socket fiber, whose scope closes the writer mid-drain.
        _            <- scope.close(Exit.unit)
        _            <- terminating.await
        closed       <- socketClosed.isDone
        logs         <- ZTestLogger.logOutput
      yield assertTrue(
        closed,
        warnings(logs).isEmpty,
        drainLogs(logs).exists(entry =>
          entry.logLevel == LogLevel.Debug &&
            entry.annotations.get("reason").contains("socket already closed")
        )
      )
    },
    test("a drain write failing because the browser closed the channel logs at debug") {
      for
        closedChannel <- disconnectWhileWriting(ClosedChannelException())
        reset         <- disconnectWhileWriting(IOException("Connection reset by peer"))
      yield
        val (channelClosed, channelLogs) = closedChannel
        val (resetClosed, resetLogs)     = reset
        assertTrue(
          channelClosed,
          resetClosed,
          warnings(channelLogs).isEmpty,
          warnings(resetLogs).isEmpty,
          drainLogs(channelLogs).exists(entry =>
            entry.logLevel == LogLevel.Debug &&
              entry.cause.prettyPrint.contains("ClosedChannelException")
          ),
          drainLogs(resetLogs).exists(entry =>
            entry.logLevel == LogLevel.Debug &&
              entry.cause.prettyPrint.contains("Connection reset by peer")
          )
        )
    },
    test("any other drain write failure logs a warning with its cause") {
      for (closed, logs) <- disconnectWhileWriting(RuntimeException("sink broke"))
      yield
        val drainWarnings = warnings(logs)
        assertTrue(
          closed,
          drainWarnings.size == 1,
          drainWarnings.head.message() == "WebSocket writer drain failed",
          drainWarnings.head.cause.prettyPrint.contains("sink broke")
        )
    },
    test("a drain stalled past the disconnect timeout logs a warning and still closes") {
      ZIO.scoped {
        for
          entered      <- Promise.make[Nothing, Unit]
          writer       <- SerialWriter.make[Int](1)(_ => entered.succeed(()).unit *> ZIO.never)
          socketClosed <- Promise.make[Nothing, Unit]
          terminating  <- termination(writer, socketClosed)
          _            <- writer.offer(1) *> entered.await
          _            <- terminating.disconnect
          stopped      <- terminating.await.fork
          _            <- TestClock.adjust(1.second).repeatUntilZIO(_ =>
                            stopped.poll.map(_.isDefined)
                          )
          closed       <- socketClosed.isDone
          logs         <- ZTestLogger.logOutput
        yield assertTrue(
          closed,
          warnings(logs).map(_.message()) ==
            Chunk("WebSocket writer drain timed out before disconnect")
        )
      }
    }
  )
