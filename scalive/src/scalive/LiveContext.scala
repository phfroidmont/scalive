package scalive

import zio.*

import scalive.upload.*

final case class LiveContext(
  staticChanged: Boolean,
  uploads: UploadRuntime = UploadRuntime.Disabled,
  navigation: LiveNavigationRuntime = LiveNavigationRuntime.Disabled)
    extends LiveContext.NavigationCapabilities

object LiveContext:
  trait HasStaticChanged:
    def staticChanged: Boolean

  trait HasUploads:
    def uploads: UploadRuntime

  trait HasNavigation:
    private[scalive] def navigation: LiveNavigationRuntime

  trait BaseCapabilities       extends HasStaticChanged with HasUploads
  trait NavigationCapabilities extends BaseCapabilities with HasNavigation

  def staticChanged: URIO[HasStaticChanged, Boolean] =
    ZIO.serviceWith[HasStaticChanged](_.staticChanged)

  def allowUpload(name: String, options: LiveUploadOptions): RIO[HasUploads, LiveUpload] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.allow(name, options))

  def disallowUpload(name: String): RIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.disallow(name))

  def upload(name: String): URIO[HasUploads, Option[LiveUpload]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.get(name))

  def cancelUpload(name: String, entryRef: String): RIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.cancel(name, entryRef))

  def consumeUploadedEntries(name: String): URIO[HasUploads, List[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.consumeCompleted(name))

  def consumeUploadedEntry(entryRef: String): URIO[HasUploads, Option[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.consume(entryRef))

  def dropUploadedEntry(entryRef: String): URIO[HasUploads, Unit] =
    ZIO.serviceWithZIO[HasUploads](_.uploads.drop(entryRef))

  def pushPatch(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.PushPatch(to)))

  def replacePatch(to: String): RIO[HasNavigation, Unit] =
    ZIO.serviceWithZIO[HasNavigation](_.navigation.request(LiveNavigationCommand.ReplacePatch(to)))
end LiveContext
