package scalive
package socket

import zio.*

final private[scalive] class SocketFlashRuntime(ref: Ref[FlashRuntimeState]) extends FlashRuntime:
  def put(kind: String, message: String): UIO[Unit] =
    ref.update(state =>
      state.copy(
        values = state.values.updated(kind, message),
        navigationValues = state.navigationValues.updated(kind, message)
      )
    )

  def clear(kind: String): UIO[Unit] =
    ref.update(state =>
      state.copy(
        values = state.values.removed(kind),
        navigationValues = state.navigationValues.removed(kind)
      )
    )

  def clearAll: UIO[Unit] =
    ref.set(FlashRuntimeState.empty)

  def get(kind: String): UIO[Option[String]] =
    ref.get.map(_.values.get(kind))

  def snapshot: UIO[Map[String, String]] =
    ref.get.map(_.values)

private[scalive] object SocketFlashRuntime:
  def resetNavigation(ref: Ref[FlashRuntimeState]): UIO[Unit] =
    ref.update(_.copy(navigationValues = Map.empty))

  def commitNavigation(ref: Ref[FlashRuntimeState]): UIO[Unit] =
    ref.update(state => state.copy(values = state.navigationValues))

  def replaceNavigation(ref: Ref[FlashRuntimeState], values: Map[String, String]): UIO[Unit] =
    ref.update(_.copy(navigationValues = values))

  def takeNavigation(ref: Ref[FlashRuntimeState]): UIO[Map[String, String]] =
    ref.modify(state => state.navigationValues -> state.copy(navigationValues = Map.empty))

  def navigationValues(ref: Ref[FlashRuntimeState]): UIO[Map[String, String]] =
    ref.get.map(_.navigationValues)
