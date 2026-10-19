package scalive

import zio.*
import zio.http.{Path, Routes}

/** The browser files Scalive serves itself, and the scripts it adds to every page's `<head>`.
  *
  * `paths` lists the URL path of every served file. `leading` scripts go before the root layout's
  * own head content and `trailing` ones after it, so the layout's scripts run between them.
  */
final private[scalive] class BrowserFiles(
  val routes: Routes[Any, Nothing],
  val paths: Vector[Path],
  val leading: Vector[HtmlElement[Nothing]],
  val trailing: Vector[HtmlElement[Nothing]])

private[scalive] object BrowserFiles:
  private val classLoader = classOf[BrowserFiles].getClassLoader

  private val runtimePrefix = "META-INF/scalive/browser/v1"
  private val guardFile     = "navigation-guard.js"
  private val connectFile   = "connect.js"
  private val runtimeFiles  = Seq(guardFile, connectFile)

  private val clientPrefix = "META-INF/scalive/live-view-client/phoenix-1.8.15-live-view-1.2.12"
  private val phoenixFile  = "phoenix.min.js"
  private val liveViewFile = "phoenix_live_view.min.js"
  private val clientFiles  = Seq(phoenixFile, liveViewFile)

  /** Loads the files for `client` below `clientPath`. The connect script connects to the encoded
    * `socketPath`.
    */
  def load(client: LiveClient, socketPath: String, clientPath: Path): UIO[BrowserFiles] =
    // The files ship in the Scalive JAR, so failing to read them is a packaging defect.
    val runtime = load(runtimePrefix, runtimeFiles, clientPath / "assets")
    client match
      case LiveClient.Custom =>
        runtime.map(runtime =>
          BrowserFiles(
            runtime.routes,
            paths(runtime, runtimeFiles),
            Vector(script(runtime, guardFile)),
            Vector.empty
          )
        )
      case LiveClient.Packaged =>
        for
          runtime <- runtime
          clients <- load(clientPrefix, clientFiles, clientPath / "live-view")
        yield BrowserFiles(
          runtime.routes ++ clients.routes,
          paths(runtime, runtimeFiles) ++ paths(clients, clientFiles),
          Vector(
            script(runtime, guardFile),
            script(clients, phoenixFile),
            script(clients, liveViewFile)
          ),
          Vector(
            runtime.trackedScript[Nothing](
              connectFile,
              defer                   := true,
              dataAttr("socket-path") := socketPath
            )
          )
        )
    end match
  end load

  private def load(prefix: String, files: Seq[String], mountPath: Path): UIO[StaticAssets] =
    StaticAssets
      .load(StaticAssetConfig.classpath(prefix, files, mountPath, classLoader = classLoader))
      .orDie

  private def paths(assets: StaticAssets, files: Seq[String]): Vector[Path] =
    files.toVector.map(file => Path.decode(assets.path(file)))

  private def script(assets: StaticAssets, file: String): HtmlElement[Nothing] =
    assets.trackedScript[Nothing](file, defer := true)
end BrowserFiles
