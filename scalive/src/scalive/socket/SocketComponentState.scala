package scalive
package socket

import zio.*

final private[scalive] case class ComponentInstance(
  cid: Int,
  identity: ComponentIdentity,
  component: LiveComponent[Any, Any, Any],
  props: Any,
  parentProps: Any,
  model: Any,
  hooks: LiveHookRuntimeState)

final private[scalive] case class ComponentRuntimeState(
  instances: Map[ComponentIdentity, ComponentInstance],
  byCid: Map[Int, ComponentIdentity],
  pendingUpdates: Map[ComponentIdentity, Vector[Any]],
  nextCid: Int):
  def instance(cid: Int): Option[ComponentInstance] =
    byCid.get(cid).flatMap(instances.get)

  def removeCids(cids: Set[Int]): ComponentRuntimeState =
    val identities = cids.flatMap(byCid.get)
    copy(
      instances = instances -- identities,
      byCid = byCid -- cids,
      pendingUpdates = pendingUpdates -- identities
    )

private[scalive] object ComponentRuntimeState:
  val empty: ComponentRuntimeState = ComponentRuntimeState(Map.empty, Map.empty, Map.empty, 1)

final private[scalive] class SocketComponentUpdateRuntime(ref: Ref[ComponentRuntimeState])
    extends ComponentUpdateRuntime:
  def sendUpdate[Props](
    componentClass: Class[?],
    id: String,
    props: Props
  ): UIO[Unit] =
    val identity = ComponentIdentity(componentClass, id)
    ref
      .modify { state =>
        if state.instances.contains(identity) then
          val pending = state.pendingUpdates.getOrElse(identity, Vector.empty) :+ props
          None -> state.copy(pendingUpdates = state.pendingUpdates.updated(identity, pending))
        else
          Some(
            s"sendUpdate ignored because component ${componentClass.getName} with id '$id' does not exist"
          ) -> state
      }.flatMap {
        case Some(message) =>
          ZIO
            .logWarning(message)
        case None => ZIO.unit
      }
