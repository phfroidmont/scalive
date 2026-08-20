package scalive.runtime.connection

import zio.json.ast.Json

import scalive.FlashKind
import scalive.render.RenderDelta
import scalive.runtime.contracts.CommandId
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.contracts.NestedRegistrationId
import scalive.runtime.kernel.NavigationOutput
import scalive.runtime.kernel.SessionEffects
import scalive.runtime.kernel.SessionFailure
import scalive.runtime.kernel.SessionRejection
import scalive.runtime.resources.UploadControlReply

final private[scalive] case class RootConnectionMetadata(
  staticChanged: Boolean,
  connectParams: Map[String, Json],
  initialFlash: Map[FlashKind, String] = Map.empty)

final private[scalive] case class ConnectionConfig private (
  ingressCapacity: Int,
  outboundReservationCapacity: Int,
  kernelMailboxCapacity: Int,
  continuationCapacity: Int,
  writerCapacity: Int,
  uploadChunkCapacity: Int,
  maxUploadChunkBytes: Int)

private[scalive] object ConnectionConfig:
  def make(
    ingressCapacity: Int,
    outboundReservationCapacity: Int,
    kernelMailboxCapacity: Int,
    continuationCapacity: Int
  ): Either[Error, ConnectionConfig] =
    make(
      ingressCapacity,
      outboundReservationCapacity,
      kernelMailboxCapacity,
      continuationCapacity,
      outboundReservationCapacity
    )

  def make(
    ingressCapacity: Int,
    outboundReservationCapacity: Int,
    kernelMailboxCapacity: Int,
    continuationCapacity: Int,
    writerCapacity: Int
  ): Either[Error, ConnectionConfig] =
    make(
      ingressCapacity,
      outboundReservationCapacity,
      kernelMailboxCapacity,
      continuationCapacity,
      writerCapacity,
      uploadChunkCapacity = 8,
      maxUploadChunkBytes = 1_000_000
    )

  def make(
    ingressCapacity: Int,
    outboundReservationCapacity: Int,
    kernelMailboxCapacity: Int,
    continuationCapacity: Int,
    writerCapacity: Int,
    uploadChunkCapacity: Int,
    maxUploadChunkBytes: Int
  ): Either[Error, ConnectionConfig] =
    if ingressCapacity <= 0 then Left(Error.InvalidIngressCapacity(ingressCapacity))
    else if outboundReservationCapacity <= 0 then
      Left(Error.InvalidOutboundReservationCapacity(outboundReservationCapacity))
    else if kernelMailboxCapacity <= 0 then
      Left(Error.InvalidKernelMailboxCapacity(kernelMailboxCapacity))
    else if continuationCapacity <= 0 then
      Left(Error.InvalidContinuationCapacity(continuationCapacity))
    else if writerCapacity <= 0 then Left(Error.InvalidWriterCapacity(writerCapacity))
    else if uploadChunkCapacity <= 0 then
      Left(Error.InvalidUploadChunkCapacity(uploadChunkCapacity))
    else if maxUploadChunkBytes <= 0 then
      Left(Error.InvalidMaxUploadChunkBytes(maxUploadChunkBytes))
    else
      Right(
        ConnectionConfig(
          ingressCapacity,
          outboundReservationCapacity,
          kernelMailboxCapacity,
          continuationCapacity,
          writerCapacity,
          uploadChunkCapacity,
          maxUploadChunkBytes
        )
      )

  enum Error:
    case InvalidIngressCapacity(capacity: Int)
    case InvalidOutboundReservationCapacity(capacity: Int)
    case InvalidKernelMailboxCapacity(capacity: Int)
    case InvalidContinuationCapacity(capacity: Int)
    case InvalidWriterCapacity(capacity: Int)
    case InvalidUploadChunkCapacity(capacity: Int)
    case InvalidMaxUploadChunkBytes(bytes: Int)
end ConnectionConfig

enum ConnectionOutput:
  case Joined(delta: RenderDelta, effects: SessionEffects)
  case Reply(command: CommandId, delta: RenderDelta, effects: SessionEffects)
  case UploadReply(
    command: CommandId,
    delta: RenderDelta,
    effects: SessionEffects,
    upload: UploadControlReply)
  case Diff(delta: RenderDelta, effects: SessionEffects)
  case JoinedNavigation(
    delta: RenderDelta,
    navigation: NavigationOutput,
    effects: SessionEffects)
  case ReplyNavigation(
    command: CommandId,
    delta: RenderDelta,
    navigation: NavigationOutput,
    effects: SessionEffects)
  case DiffNavigation(
    delta: RenderDelta,
    navigation: NavigationOutput,
    effects: SessionEffects)
  case Rejected(command: CommandId, rejection: SessionRejection)

sealed abstract class ConnectionError(message: String) extends Exception(message)

object ConnectionError:
  final case class RenderCompilationFailed(cause: Throwable)
      extends ConnectionError(s"render compilation failed: ${cause.getMessage}")
  final case class SessionFailed(failure: SessionFailure)
      extends ConnectionError(s"session failed: ${failure.getMessage}")
  final case class OutboundFailed(details: String)
      extends ConnectionError(s"outbound reservations failed: $details")
  final case class SinkFailed(cause: Throwable)
      extends ConnectionError(s"connection sink failed: ${cause.getMessage}")
  final case class UploadFailed(cause: Throwable)
      extends ConnectionError(s"upload operation failed: ${cause.getMessage}")
  final case class LinkedChildFailed(child: LifecycleId, cause: ConnectionError)
      extends ConnectionError(
        s"linked child lifecycle ${child.value} failed: ${cause.getMessage}"
      )
  final case class LinkedChildJoinFailed(
    registration: NestedRegistrationId,
    cause: ConnectionError)
      extends ConnectionError(
        s"linked child registration ${registration.value} failed to join: ${cause.getMessage}"
      )
  final case class IngressSaturated(capacity: Int)
      extends ConnectionError(s"connection ingress capacity $capacity is saturated")
  case object IngressFailed extends ConnectionError("connection ingress failed unexpectedly")
  final case class KernelRejected(rejection: SessionRejection)
      extends ConnectionError(s"kernel rejected an event: $rejection")
  final case class DuplicateCommand(command: CommandId)
      extends ConnectionError(s"command ${command.value} is already registered")
  case object Closed extends ConnectionError("connection is closed")
