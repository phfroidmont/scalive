package scalive

import zio.*
import zio.http.URL

final private[scalive] case class LiveHookRuntimeState(
  eventHooks: Vector[StoredEventHook],
  paramsHooks: Vector[StoredParamsHook],
  infoHooks: Vector[StoredInfoHook],
  asyncHooks: Vector[StoredAsyncHook],
  afterRenderHooks: Vector[StoredAfterRenderHook])

private[scalive] object LiveHookRuntimeState:
  val empty: LiveHookRuntimeState =
    LiveHookRuntimeState(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

final private[scalive] case class StoredEventHook(
  id: String,
  run: (Any, Any, LiveEvent) => RIO[LiveView.UpdateContext, LiveEventResult[Any]])

final private[scalive] case class StoredParamsHook(
  id: String,
  run: (Any, URL) => RIO[LiveView.ParamsContext, LiveHookResult[Any]])

final private[scalive] case class StoredInfoHook(
  id: String,
  run: (Any, Any) => RIO[LiveView.UpdateContext, LiveHookResult[Any]])

final private[scalive] case class StoredAsyncHook(
  id: String,
  run: (Any, Any, LiveAsyncEvent) => RIO[LiveView.UpdateContext, LiveHookResult[Any]])

final private[scalive] case class StoredAfterRenderHook(
  id: String,
  run: Any => RIO[LiveView.UpdateContext, Any])
