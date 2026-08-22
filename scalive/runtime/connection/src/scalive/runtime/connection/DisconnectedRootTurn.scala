package scalive.runtime.connection

import zio.Task
import zio.UIO
import zio.ZIO
import zio.http.URL

import scalive.*
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.kernel.NavigationRequest
import scalive.runtime.resources.OwnerId

final private[scalive] class DisconnectedRootTurn[Msg, Model] private[connection] (
  val lifecycle: LifecycleId,
  journal: RootTurnJournal,
  initialUrl: URL):

  private val metadata = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)

  def mountContext: MountContext[Msg, Model] =
    RootMountContext.disconnected(initialUrl, journal)

  def runParams(
    initial: Model,
    destination: URL,
    prepared: RootParamsHandler[Msg, Model]
  ): Task[Model] =
    val context = RootParamsContext[Msg, Model](
      metadata,
      destination,
      journal,
      connected = false
    )
    val hooked =
      if prepared.runHooks then
        journal.hookRegistry[Msg, Model].flatMap { registry =>
          registry.params.foldLeft[Task[LiveHookResult[Model]]](
            ZIO.succeed(LiveHookResult.cont(initial))
          ) { (effect, hook) =>
            effect.flatMap {
              case halted @ LiveHookResult.Halt(_) => ZIO.succeed(halted)
              case LiveHookResult.Continue(model)  => hook.invoke(model, destination, context)
            }
          }
        }
      else ZIO.succeed(LiveHookResult.cont(initial))

    hooked.flatMap {
      case LiveHookResult.Halt(model)     => ZIO.succeed(model)
      case LiveHookResult.Continue(model) => prepared.run(model, context)
    }

  def runAfterRender(model: Model): Task[Unit] =
    for
      hooks <- journal.hookRegistry[Msg, Model]
      context = RootAfterRenderContext[Msg, Model](metadata, journal, connected = false)
      _ <- ZIO.foreachDiscard(hooks.afterRender)(_.invoke(model, context))
    yield ()

  def navigation: UIO[Option[NavigationRequest]] = journal.navigationWithFlash

  def flash: UIO[Map[FlashKind, String]] = journal.flash.get

  /** Shared request journal used by disconnected component rendering. */
  private[scalive] def componentJournal: RootTurnJournal = journal
end DisconnectedRootTurn

private[scalive] object DisconnectedRootTurn:
  def make[Msg, Model](
    hooks: LiveHooks[Msg, Model],
    initialUrl: URL,
    initialFlash: Map[FlashKind, String]
  ): Task[DisconnectedRootTurn[Msg, Model]] =
    for
      lifecycle <-
        ZIO
          .fromEither(LifecycleId.fresh()).mapError(error => IllegalStateException(error.toString))
      journal <- RootTurnJournal.make(
                   OwnerId.Root(lifecycle),
                   RootHookRegistry.fromStatic(hooks),
                   initialFlash
                 )
    yield new DisconnectedRootTurn(lifecycle, journal, initialUrl)
