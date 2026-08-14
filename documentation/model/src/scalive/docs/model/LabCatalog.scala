package scalive.docs.model

final case class LabDescriptor(
  id: String,
  title: String,
  description: String,
  route: String,
  topics: Vector[String],
  actionLabel: String)

object LabCatalog:
  val Authentication = LabDescriptor(
    id = "authentication",
    title = "Authentication lab",
    description =
      "Sign in through ordinary HTTP, then mount and reset a protected LiveView session.",
    route = "/examples/authentication/lab",
    topics = Vector("authentication", "sessions", "security", "mount aspects"),
    actionLabel = "Open authentication lab"
  )

  val entries: Vector[LabDescriptor] = Vector(Authentication)

  private val byId = entries.map(lab => lab.id -> lab).toMap

  def get(id: String): Option[LabDescriptor] = byId.get(id)
