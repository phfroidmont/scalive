package scalive.examples.components

import zio.ZIO

import scalive.*

final class ComponentsLiveView extends LiveView[ComponentsLiveView.Msg, ComponentsLiveView.Model]:
  import ComponentsLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(propRevision = 0, status = "The parent page has not sent an update."))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.UpdateScalaProps =>
      val revision = model.propRevision + 1
      ctx.components
        .sendUpdate(ScalaVote, scalaProps(revision))
        .as(
          model.copy(
            propRevision = revision,
            status = s"The parent sent props revision $revision to '${ScalaVote.id}'."
          )
        )

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Components"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Stateful voting components"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Each VoteComponent owns its vote count. The parent can route typed browser events and props to an exact component instance."
        )
      ),
      div(
        cls := "mb-6 rounded-box border border-base-300 bg-base-200 p-5",
        h2(cls := "font-semibold", "Parent page state"),
        p(cls  := "mt-1 text-sm text-base-content/70", model.status),
        div(
          cls := "mt-4 flex flex-wrap gap-3",
          button(
            typ := "button",
            cls := "btn btn-primary",
            on.click.to(ScalaVote)(VoteComponent.Msg.Vote),
            "Send a targeted vote"
          ),
          button(
            typ := "button",
            cls := "btn btn-outline",
            on.click(Msg.UpdateScalaProps),
            "Parent sends updated props"
          )
        )
      ),
      div(
        cls := "grid gap-5 lg:grid-cols-2",
        ScalaVote.render(scalaProps(revision = 0)),
        ZioVote.render(
          VoteComponent.Props(
            title = "ZIO ecosystem",
            description =
              "This second instance proves that local state is isolated by component identity."
          )
        )
      )
    )
end ComponentsLiveView

object ComponentsLiveView:
  final case class Model(propRevision: Int, status: String)

  enum Msg:
    case UpdateScalaProps

  private val ScalaVote = component(VoteComponent, "scala-vote")
  private val ZioVote   = component(VoteComponent, "zio-vote")

  private def scalaProps(revision: Int): VoteComponent.Props =
    VoteComponent.Props(
      title = if revision == 0 then "Scala language" else s"Scala language, revision $revision",
      description =
        if revision == 0 then
          "Vote locally, or use the parent controls to target this stable component."
        else "These props came from sendUpdate; the component's local vote count was preserved."
    )
