package scalive
package streams

import zio.*

trait StreamRuntime:
  def stream[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt,
    reset: Boolean,
    limit: Option[StreamLimit]
  ): Task[LiveStream[A]]

  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean
  ): Task[LiveStream[A]]

  def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]]

  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]]

  def get[A](definition: LiveStreamDef[A]): UIO[Option[LiveStream[A]]]

object StreamRuntime:
  val Disabled: StreamRuntime = new StreamRuntime:
    def stream[A](
      definition: LiveStreamDef[A],
      items: Iterable[A],
      at: StreamAt,
      reset: Boolean,
      limit: Option[StreamLimit]
    ): Task[LiveStream[A]] =
      ZIO.fail(new IllegalStateException("Stream runtime is not available"))

    def insert[A](
      definition: LiveStreamDef[A],
      item: A,
      at: StreamAt,
      limit: Option[StreamLimit],
      updateOnly: Boolean
    ): Task[LiveStream[A]] =
      ZIO.fail(new IllegalStateException("Stream runtime is not available"))

    def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]] =
      ZIO.fail(new IllegalStateException("Stream runtime is not available"))

    def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]] =
      ZIO.fail(new IllegalStateException("Stream runtime is not available"))

    def get[A](definition: LiveStreamDef[A]): UIO[Option[LiveStream[A]]] = ZIO.none
