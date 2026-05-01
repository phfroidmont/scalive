package scalive
package upload

import zio.*

private[scalive] trait UploadRuntime:
  def allow(name: String, options: LiveUploadOptions): Task[LiveUpload]
  def disallow(name: String): Task[Unit]
  def get(name: String): UIO[Option[LiveUpload]]
  def cancel(name: String, entryRef: String): Task[Unit]
  def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]]
  def consume(entryRef: String): UIO[Option[LiveUploadedEntry]]
  def drop(entryRef: String): UIO[Unit]

private[scalive] object UploadRuntime:
  val Disabled: UploadRuntime = new UploadRuntime:
    def allow(name: String, options: LiveUploadOptions): Task[LiveUpload] =
      ZIO.fail(new IllegalStateException("Upload runtime is not available"))

    def disallow(name: String): Task[Unit] =
      ZIO.fail(new IllegalStateException("Upload runtime is not available"))

    def get(name: String): UIO[Option[LiveUpload]] = ZIO.none

    def cancel(name: String, entryRef: String): Task[Unit] =
      ZIO.fail(new IllegalStateException("Upload runtime is not available"))

    def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]] = ZIO.succeed(Nil)

    def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] = ZIO.none

    def drop(entryRef: String): UIO[Unit] = ZIO.unit
