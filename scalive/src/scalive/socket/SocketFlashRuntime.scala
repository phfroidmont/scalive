package scalive
package socket

import zio.*

final private[scalive] class SocketFlashRuntime(ref: Ref[FlashRuntimeState]) extends FlashRuntime:
  def put(kind: String, message: String): UIO[Unit] =
    ref.update(state => state.copy(values = state.values.updated(kind, message)))

  def clear(kind: String): UIO[Unit] =
    ref.update(state => state.copy(values = state.values.removed(kind)))

  def clearAll: UIO[Unit] =
    ref.set(FlashRuntimeState.empty)

  def get(kind: String): UIO[Option[String]] =
    ref.get.map(_.values.get(kind))

  def snapshot: UIO[Map[String, String]] =
    ref.get.map(_.values)
