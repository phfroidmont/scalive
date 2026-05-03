package scalive

import zio.*
import zio.stream.ZStream

private[scalive] trait SubscriptionRuntime[Msg]:
  def start(name: String)(stream: ZStream[Any, Nothing, Msg]): Task[Unit]
  def replace(name: String)(stream: ZStream[Any, Nothing, Msg]): Task[Unit]
  def cancel(name: String): Task[Unit]

private[scalive] object SubscriptionRuntime:
  object Disabled extends SubscriptionRuntime[Any]:
    def start(name: String)(stream: ZStream[Any, Nothing, Any]): Task[Unit] = ZIO.unit

    def replace(name: String)(stream: ZStream[Any, Nothing, Any]): Task[Unit] = ZIO.unit

    def cancel(name: String): Task[Unit] = ZIO.unit
