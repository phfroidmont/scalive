package scalive
package socket

import zio.*
import zio.json.ast.Json
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.Payload

final private[scalive] case class UploadConfigState(
  name: String,
  ref: String,
  options: LiveUploadOptions,
  errors: List[(String, Json)] = Nil,
  entryOrder: Vector[String] = Vector.empty,
  cancelledRefs: Set[String] = Set.empty)

final private[scalive] case class UploadEntryState(
  uploadName: String,
  uploadRef: String,
  ref: String,
  name: String,
  contentType: String,
  size: Long,
  relativePath: Option[String],
  lastModified: Option[Long],
  clientMeta: Option[Json],
  token: Option[String],
  joined: Boolean,
  bytes: Chunk[Byte],
  progress: Int,
  preflighted: Boolean,
  cancelled: Boolean,
  valid: Boolean,
  errors: List[Json],
  externalMeta: Option[Json.Obj],
  writer: LiveUploadWriter,
  writerState: Option[Any],
  writerMeta: Option[Json.Obj],
  writerClosed: Boolean)

final private[scalive] case class UploadRuntimeState(
  configs: Map[String, UploadConfigState],
  refsToNames: Map[String, String],
  entries: Map[String, UploadEntryState],
  tokens: Map[String, String]):
  def configByRef(ref: String): Option[UploadConfigState] =
    refsToNames.get(ref).flatMap(configs.get)

  def removeUploadByRef(uploadRef: String): UploadRuntimeState =
    refsToNames.get(uploadRef) match
      case Some(name) => removeUploadByName(name)
      case None       => this

  def removeUploadByName(name: String): UploadRuntimeState =
    configs.get(name) match
      case Some(config) =>
        val nextBase = copy(
          configs = configs.removed(name),
          refsToNames = refsToNames.removed(config.ref)
        )
        nextBase.removeEntries(config.entryOrder.toSet)
      case None => this

  def removeEntry(entryRef: String): UploadRuntimeState =
    removeEntries(Set(entryRef))

  def removeEntries(entryRefs: Set[String]): UploadRuntimeState =
    if entryRefs.isEmpty then this
    else
      val nextConfigs = configs.view
        .mapValues(config =>
          config.copy(entryOrder = config.entryOrder.filterNot(entryRefs.contains))
        )
        .toMap
      UploadRuntimeState(
        configs = nextConfigs,
        refsToNames = refsToNames,
        entries = entries -- entryRefs,
        tokens = tokens.filterNot { case (_, ref) => entryRefs.contains(ref) }
      )
end UploadRuntimeState

private[scalive] object UploadRuntimeState:
  val empty: UploadRuntimeState =
    UploadRuntimeState(Map.empty, Map.empty, Map.empty, Map.empty)

final private[scalive] case class StreamInsertState(
  domId: String,
  at: Int,
  item: Any,
  limit: Option[Int],
  updateOnly: Option[Boolean])

final private[scalive] case class StreamState(
  name: String,
  ref: String,
  domId: Any => String,
  inserts: List[StreamInsertState],
  deleteIds: List[String],
  reset: Boolean)

final private[scalive] case class StreamRuntimeState(
  streams: Map[String, StreamState],
  nextRef: Long)

private[scalive] object StreamRuntimeState:
  val empty: StreamRuntimeState =
    StreamRuntimeState(Map.empty, 0L)

final private[scalive] case class RuntimeState[Msg, Model](
  lv: LiveView[Msg, Model],
  ctx: LiveContext,
  meta: WebSocketMessage.Meta,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  outHub: Hub[(Payload, WebSocketMessage.Meta)],
  ref: Ref[(Var[Model], HtmlElement)],
  lvStreamRef: SubscriptionRef[ZStream[Any, Nothing, Msg]],
  navigationRef: Ref[Option[LiveNavigationCommand]],
  uploadRef: Ref[UploadRuntimeState],
  streamRef: Ref[StreamRuntimeState],
  clientEventsRef: Ref[Vector[Diff.Event]],
  patchRedirectCountRef: Ref[Int],
  initDiff: Diff)
