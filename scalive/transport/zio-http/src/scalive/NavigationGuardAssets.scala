package scalive

import zio.Task
import zio.http.{Path, Routes}

/** The independently versioned browser runtime used by [[navigation.guardWhen]].
  *
  * Add [[routes]] to the application's HTTP routes and render [[script]] before the application
  * LiveSocket bundle in the root layout.
  */
final class NavigationGuardAssets private (assets: StaticAssets):
  /** GET and HEAD routes for the versioned navigation-guard runtime. */
  val routes: Routes[Any, Nothing] = assets.routes

  /** A deferred, Phoenix-tracked script element for the navigation-guard runtime. */
  def script: HtmlElement[Nothing] =
    assets.trackedScript(NavigationGuardAssets.runtimeFile, defer := true)

object NavigationGuardAssets:
  /** Default HTTP mount for the isolated navigation-guard asset graph. */
  val defaultMountPath: Path = Path.empty / "_scalive" / "assets"

  private val resourcePrefix = "META-INF/scalive/navigation-guard/v1"
  private val runtimeFile    = "navigation-guard.js"

  /** Loads and validates the fixed, one-file classpath asset graph. */
  def load(
    mountPath: Path = defaultMountPath,
    classLoader: ClassLoader = Thread.currentThread().getContextClassLoader
  ): Task[NavigationGuardAssets] =
    StaticAssets
      .load(
        StaticAssetConfig.classpath(
          resourcePrefix,
          Seq(runtimeFile),
          mountPath = mountPath,
          classLoader = classLoader
        )
      ).map(new NavigationGuardAssets(_))
