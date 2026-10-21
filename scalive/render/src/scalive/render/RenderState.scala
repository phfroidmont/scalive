package scalive.render

import scalive.FlashKey
import scalive.FlashValue
import scalive.streams.LiveStream
import scalive.streams.LiveStreamDef
import scalive.upload.LiveUpload
import scalive.upload.LiveUploadDef

/** Runtime-owned state that one render of a LiveView or component reads besides its input. */
private[scalive] trait RenderState:
  def flash: Map[FlashKey, FlashValue]

  /** The current upload for `definition`, or `None` when the owner has not allowed it. */
  def upload[R](definition: LiveUploadDef[R]): Option[LiveUpload[R]]

  /** The current stream for `definition`, or `None` when the owner has not created it. */
  def stream[A](definition: LiveStreamDef[A, ?]): Option[LiveStream[A]]

private[scalive] object RenderState:
  /** State with flash values and no uploads or streams. */
  def apply(flash: Map[FlashKey, FlashValue]): RenderState = FlashOnly(flash)

  val empty: RenderState = RenderState(Map.empty)

  final private case class FlashOnly(flash: Map[FlashKey, FlashValue]) extends RenderState:
    def upload[R](definition: LiveUploadDef[R]): Option[LiveUpload[R]]    = None
    def stream[A](definition: LiveStreamDef[A, ?]): Option[LiveStream[A]] = None
