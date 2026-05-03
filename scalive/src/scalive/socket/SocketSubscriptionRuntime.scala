package scalive
package socket

import zio.*
import zio.stream.*

final private[scalive] class SocketSubscriptionRuntime[Msg](
  ref: SubscriptionRef[Map[String, ZStream[Any, Nothing, Msg]]])
    extends SubscriptionRuntime[Msg]:

  def start(name: String)(stream: ZStream[Any, Nothing, Msg]): Task[Unit] =
    if name.isEmpty then
      ZIO.fail(new IllegalArgumentException("Subscription name must not be empty"))
    else
      ref
        .modify { current =>
          if current.contains(name) then
            Left(
              new IllegalArgumentException(s"Subscription '$name' is already started")
            )            -> current
          else Right(()) -> current.updated(name, stream)
        }.flatMap(ZIO.fromEither(_))

  def replace(name: String)(stream: ZStream[Any, Nothing, Msg]): Task[Unit] =
    if name.isEmpty then
      ZIO.fail(new IllegalArgumentException("Subscription name must not be empty"))
    else ref.update(_.updated(name, stream))

  def cancel(name: String): Task[Unit] =
    ref.update(_.removed(name))

private[scalive] object SocketSubscriptionRuntime:
  def stream[Msg](
    ref: SubscriptionRef[Map[String, ZStream[Any, Nothing, Msg]]]
  ): ZStream[Any, Nothing, Msg] =
    (ZStream.fromZIO(ref.get) ++ ref.changes)
      .map(subscriptions => ZStream.mergeAllUnbounded()(subscriptions.values.toList*))
      .flatMapParSwitch(1)(identity)
