package scalive
package socket

import zio.*

final private[scalive] class SocketTitleRuntime(
  titleRef: Ref[Option[String]])
    extends TitleRuntime:
  def set(title: String): UIO[Unit] = titleRef.set(Some(title)).unit
  def drain: UIO[Option[String]]    = titleRef.getAndSet(None)
