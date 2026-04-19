package scalive

import zio.*

import scalive.upload.*

final case class LiveContext(
  staticChanged: Boolean,
  uploads: UploadRuntime = UploadRuntime.Disabled)

object LiveContext:
  def staticChanged: URIO[LiveContext, Boolean] = ZIO.serviceWith[LiveContext](_.staticChanged)

  def allowUpload(name: String, options: LiveUploadOptions): RIO[LiveContext, LiveUpload] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.allow(name, options))

  def disallowUpload(name: String): RIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.disallow(name))

  def upload(name: String): URIO[LiveContext, Option[LiveUpload]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.get(name))

  def cancelUpload(name: String, entryRef: String): RIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.cancel(name, entryRef))

  def consumeUploadedEntries(name: String): URIO[LiveContext, List[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.consumeCompleted(name))

  def consumeUploadedEntry(entryRef: String): URIO[LiveContext, Option[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.consume(entryRef))

  def dropUploadedEntry(entryRef: String): URIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.drop(entryRef))
