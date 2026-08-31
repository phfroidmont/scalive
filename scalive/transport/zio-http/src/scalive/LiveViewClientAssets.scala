package scalive

import zio.Task
import zio.http.{Path, Routes}

/** The supported Phoenix and Phoenix LiveView browser clients.
  *
  * Add [[routes]] to the application's HTTP routes. Render [[phoenixScript]] before
  * [[liveViewScript]], then render the application script which constructs and connects
  * `LiveSocket`.
  */
final class LiveViewClientAssets private (assets: StaticAssets):
  /** GET and HEAD routes for the versioned client files. */
  val routes: Routes[Any, Nothing] = assets.routes

  /** A deferred, Phoenix-tracked script element for Phoenix 1.8.9. */
  def phoenixScript: HtmlElement[Nothing] =
    assets.trackedScript(LiveViewClientAssets.phoenixFile, defer := true)

  /** A deferred, Phoenix-tracked script element for Phoenix LiveView 1.2.10. */
  def liveViewScript: HtmlElement[Nothing] =
    assets.trackedScript(LiveViewClientAssets.liveViewFile, defer := true)

object LiveViewClientAssets:
  /** Default HTTP mount for the isolated client asset graph. */
  val defaultMountPath: Path = Path.empty / "_scalive" / "live-view"

  private val resourcePrefix =
    "META-INF/scalive/live-view-client/phoenix-1.8.9-live-view-1.2.10"
  private val phoenixFile  = "phoenix.min.js"
  private val liveViewFile = "phoenix_live_view.min.js"

  /** Loads and validates the fixed, two-file classpath asset graph. */
  def load(
    mountPath: Path = defaultMountPath,
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): Task[LiveViewClientAssets] =
    StaticAssets
      .load(
        StaticAssetConfig.classpath(
          resourcePrefix,
          Seq(phoenixFile, liveViewFile),
          mountPath = mountPath,
          classLoader = classLoader
        )
      ).map(new LiveViewClientAssets(_))
