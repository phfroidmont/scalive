package uploads

import zio.*
import zio.json.ast.Json

import scalive.*

// docs:start uploads-external-uploader
final case class PreparedPut(key: String, uploadId: String, signedUrl: String)
final case class ReservedObject(key: String, uploadId: String)

trait ObjectStore:
  def authorize(client: UploadClientMetadata): Task[Unit]
  def preparePut(sizeBytes: Long): Task[PreparedPut]
  def abort(key: String, uploadId: String): Task[Unit]

final class ObjectStoreUploader(objectStore: ObjectStore)
    extends LiveUploadExternalUploader[ReservedObject]:
  def preflight(client: UploadClientMetadata): Task[LiveExternalUploadResult[ReservedObject]] =
    for
      _        <- objectStore.authorize(client)
      prepared <- objectStore.preparePut(client.sizeBytes)
    yield
      val config = ExternalUploadClientConfig(
        Json.Obj(
          "uploader" -> Json.Str("object-store"),
          "url"      -> Json.Str(prepared.signedUrl),
          "method"   -> Json.Str("PUT")
        )
      )
      LiveExternalUploadResult.Ready(config, ReservedObject(prepared.key, prepared.uploadId))

  override def discard(result: ReservedObject): Task[Unit] =
    objectStore.abort(result.key, result.uploadId)

def documents(objectStore: ObjectStore) = LiveUploadDef.external(
  name = "document",
  accept = LiveUploadAccept.only(".pdf"),
  uploader = ObjectStoreUploader(objectStore),
  maxFileSize = 20L * 1024L * 1024L
)
// docs:end uploads-external-uploader
