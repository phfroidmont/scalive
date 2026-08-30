package scalive.docs.examples

import java.util.UUID

import zio.*

import scalive.*

// docs:start connected-resource-example
final case class LifecycleRegistration(id: String)

trait LifecycleRegistrations:
  def register(owner: String): UIO[LifecycleRegistration]
  def unregister(registration: LifecycleRegistration): UIO[Unit]

final class ConnectedResourceExample(
  instanceId: String,
  registrations: LifecycleRegistrations)
    extends LiveView[ConnectedResourceExample.Msg, ConnectedResourceExample.Model]:
  import ConnectedResourceExample.*

  def mount(ctx: MountContext): Task[Model] = ctx.connection match
    case Connection.Disconnected         => ZIO.succeed(Model())
    case Connection.Connected(connected) =>
      connected.resources
        .acquireRelease(registrations.register(instanceId))(registrations.unregister)
        .map(registration => Model(Some(registration)))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Check =>
      ZIO.succeed(model.copy(checks = model.checks + 1))
    case Msg.Reset =>
      ZIO.succeed(model.copy(checks = 0))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val status = model.map(_.registration.fold("Waiting for connected mount")(_ => "Acquired"))
    val handle = model.map(_.registration.fold("Not acquired")(_.id))

    div(
      cls := "docs-managed-work",
      sectionTag(
        cls        := "docs-managed-work-state",
        aria.label := "Connected lifecycle registration",
        p("Status", strong(dataAttr("connected-resource-status") := "", status)),
        p("Handle", code(dataAttr("connected-resource-handle") := "", handle)),
        p(
          "Model-only checks",
          strong(dataAttr("connected-resource-checks") := "", model.map(_.checks.toString))
        )
      ),
      div(
        cls := "docs-managed-work-controls",
        button(typ := "button", on.click(Msg.Check), "Update model"),
        button(typ := "button", on.click(Msg.Reset), "Reset checks")
      )
    )
end ConnectedResourceExample

object ConnectedResourceExample:
  final case class Model(
    registration: Option[LifecycleRegistration] = None,
    checks: Int = 0)

  enum Msg:
    case Check, Reset
// docs:end connected-resource-example

private[docs] object ConnectedResourceExamplePreview:
  def apply(instanceId: String): ConnectedResourceExample =
    new ConnectedResourceExample(
      instanceId,
      new LifecycleRegistrations:
        def register(owner: String): UIO[LifecycleRegistration] =
          ZIO.succeed(LifecycleRegistration(s"registration:$owner:${UUID.randomUUID()}"))

        def unregister(registration: LifecycleRegistration): UIO[Unit] =
          ZIO.logDebug(s"Released connected resource '${registration.id}'")
    )
