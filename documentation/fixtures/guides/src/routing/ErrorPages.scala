package routing

import zio.*
import zio.http.codec.PathCodec
import zio.http.{Request, Status}

import scalive.*

// docs:start routing-error-page
object SiteErrorPage extends LiveErrorPage:
  override def pageTitle(status: Status, request: Request) = Some(heading(status))

  def view(status: Status, request: Request): HtmlElement[Nothing] =
    mainTag(h1(heading(status)), a(href := "/", "Back to the start"))

  private def heading(status: Status): String = status match
    case Status.NotFound  => "Page not found"
    case Status.Forbidden => "You cannot open this page"
    case _                => "Something went wrong"
// docs:end routing-error-page

final case class Patient(id: Int, name: String)

final class Patients(known: Map[Int, String]):
  def find(id: Int): UIO[Option[Patient]] = ZIO.succeed(known.get(id).map(Patient(id, _)))

object PatientRoutes:
  val patient = live / "patients" / PathCodec.int("id")

// docs:start routing-live-not-found
final class PatientLiveView(patients: Patients) extends LiveView.Routed.Eventless[Patient, Int]:
  def mount(id: Int, ctx: MountContext): Task[Patient] =
    patients.find(id).someOrFail(LiveNotFound(s"patient $id"))
  // docs:end routing-live-not-found

  def view(model: Signal[Patient]): HtmlElement[Nothing] = h1(model.map(_.name))

object ErrorPages:
  val patients = Patients(Map(1 -> "Ada Lovelace"))

  // docs:start routing-error-page-router
  val application = Live.router.withErrorPage(SiteErrorPage)(
    PatientRoutes.patient -> PatientLiveView(patients)
  )
  // docs:end routing-error-page-router
