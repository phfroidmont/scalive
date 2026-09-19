package scalive

import zio.{Task, ZIO}

/** Ordered, route-local connected setup, retaining each declaration's context projection. */
final private[scalive] class LiveConnectedResourceInitializers[-Ctx] private (
  initialize: (Ctx, ConnectedResources) => Task[Unit],
  private val isEmpty: Boolean = false):
  def run(context: Ctx, resources: ConnectedResources): Task[Unit] =
    ZIO.suspendSucceed(initialize(context, resources))

  def andThen[In <: Ctx](
    next: LiveConnectedResourceInitializers[In]
  ): LiveConnectedResourceInitializers[In] =
    if isEmpty then next
    else if next.isEmpty then this
    else
      new LiveConnectedResourceInitializers((context, resources) =>
        run(context, resources) *> next.run(context, resources)
      )

  def contramap[In](select: In => Ctx): LiveConnectedResourceInitializers[In] =
    if isEmpty then LiveConnectedResourceInitializers.empty
    else
      new LiveConnectedResourceInitializers((context, resources) => run(select(context), resources))

private[scalive] object LiveConnectedResourceInitializers:
  val empty: LiveConnectedResourceInitializers[Any] =
    new LiveConnectedResourceInitializers((_, _) => ZIO.unit, isEmpty = true)

  def apply[Ctx, Result <: Unit](
    initialize: (Ctx, ConnectedResources) => Task[Result]
  ): LiveConnectedResourceInitializers[Ctx] =
    new LiveConnectedResourceInitializers((context, resources) =>
      initialize(context, resources).unit
    )
