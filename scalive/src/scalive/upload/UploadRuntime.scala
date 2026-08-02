package scalive
package upload

import zio.*

private[scalive] trait UploadRuntime:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]]
  def disallow[R](definition: LiveUploadDef[R]): Task[Unit]
  def get[R](definition: LiveUploadDef[R]): UIO[Option[LiveUpload[R]]]
  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]]
  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])]
  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])]

private[scalive] object UploadRuntime:
  val Disabled: UploadRuntime = new UploadRuntime:
    private def unavailable[A]: Task[A] =
      ZIO.fail(new IllegalStateException("Upload runtime is not available"))

    def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]]      = unavailable
    def disallow[R](definition: LiveUploadDef[R]): Task[Unit]            = unavailable
    def get[R](definition: LiveUploadDef[R]): UIO[Option[LiveUpload[R]]] = ZIO.none
    def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]]        = unavailable
    def consume[R, A](
      entry: LiveUploadEntry[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): Task[(A, LiveUpload[R])] = unavailable
    def consumeCompleted[R, A](
      definition: LiveUploadDef[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ): Task[(List[A], LiveUpload[R])] = unavailable
