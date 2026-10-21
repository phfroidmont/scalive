package routing

import java.util.UUID

import zio.*
import zio.schema.Schema
import zio.schema.derived

import scalive.*

// docs:start routing-return-to
final case class ReturnTo private (path: String)

object ReturnTo:
  // Only a path on this site, so a crafted link cannot send the visitor elsewhere after sign-in.
  def from(raw: String): Option[ReturnTo] =
    val sameSite = raw.startsWith("/") && !raw.startsWith("//") && !raw.startsWith("/\\")
    Option.when(sameSite)(ReturnTo(raw))

object LoginRoutes:
  val login = live / "login"

  val loginWithReturn =
    login.queryOptional[String]("returnTo").mapParams(_.flatMap(ReturnTo.from))(_.map(_.path))
// docs:end routing-return-to

// docs:start routing-return-to-view
final class LoginLiveView extends LiveView.Routed.Eventless[Option[ReturnTo], Option[ReturnTo]]:
  def mount(returnTo: Option[ReturnTo], ctx: MountContext) = ZIO.succeed(returnTo)
  // docs:end routing-return-to-view

  def view(model: Signal[Option[ReturnTo]]): HtmlElement[Nothing] =
    form(
      input(typ := "hidden", nameAttr := "returnTo", value := model.map(_.fold("")(_.path))),
      button("Sign in")
    )

object LoginLinks:
  // docs:start routing-return-to-links
  val plain = LoginRoutes.login.location
  // plain.href == "/login"

  val returning = LoginRoutes.loginWithReturn.location(ReturnTo.from("/account"))
  // returning.href == "/login?returnTo=%2Faccount"
  // docs:end routing-return-to-links

// docs:start routing-reset-link
enum ResetLink:
  case Usable(id: UUID, code: UUID)
  case Unusable

object ResetLink:
  final case class Query(id: Option[UUID], code: Option[UUID]) derives Schema

  def parse(query: Query): ResetLink =
    (query.id, query.code) match
      case (Some(id), Some(code)) => Usable(id, code)
      case _                      => Unusable

  def encode(link: ResetLink): Query = link match
    case Usable(id, code) => Query(Some(id), Some(code))
    case Unusable         => Query(None, None)

object ResetRoutes:
  val resetPassword =
    (live / "reset-password").query[ResetLink.Query].mapParams(ResetLink.parse)(ResetLink.encode)
// docs:end routing-reset-link

final class ResetPasswordLiveView extends LiveView.Routed.Eventless[ResetLink, ResetLink]:
  def mount(link: ResetLink, ctx: MountContext) = ZIO.succeed(link)

  def view(model: Signal[ResetLink]): HtmlElement[Nothing] =
    mainTag(
      model.matchCases { is =>
        (
          is.Usable(_ => p("Choose a new password.")),
          is.Unusable(_ => p("This link cannot be used."))
        )
      }
    )

object QueryValues:
  // docs:start routing-query-registration
  val application = Live.router(
    LoginRoutes.loginWithReturn -> LoginLiveView(),
    ResetRoutes.resetPassword   -> ResetPasswordLiveView()
  )
  // docs:end routing-query-registration
