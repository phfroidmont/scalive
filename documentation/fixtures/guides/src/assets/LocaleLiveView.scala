package assets

import zio.*

import scalive.*

final class LocaleLiveView extends LiveView.Eventless[Option[String]]:
  // docs:start assets-connect-params
  private val Locale = ConnectParam[String]("locale")

  def mount(ctx: MountContext): Task[Option[String]] =
    val locale = ctx.connection match
      case Connection.Connected(capabilities) => capabilities.connectParam(Locale)
      case Connection.Disconnected            => None
    ZIO.succeed(locale)
  // docs:end assets-connect-params

  def view(model: Signal[Option[String]]): HtmlElement[Nothing] =
    p(model.map(locale => s"Locale: ${locale.getOrElse("unknown")}"))
