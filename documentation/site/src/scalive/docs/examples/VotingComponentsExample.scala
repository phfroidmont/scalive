package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*

// docs:start voting-components-example
object VoteComponent
    extends LiveComponent.WithOutput[
      VoteComponent.Props,
      VoteComponent.Msg,
      VoteComponent.Model,
      VoteComponent.Output
    ]:
  final case class Props(
    id: String,
    title: String,
    description: String,
    revision: Int,
    resetEpoch: Int)
  final case class Model(votes: Int, resetEpoch: Int)

  enum Msg:
    case Vote
    case Reset

  enum Output:
    case VoteChanged(id: String, votes: Int)

  def mount(props: Props, ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(0, props.resetEpoch))

  override def update(props: Props, model: Model, ctx: UpdateContext): Task[Model] =
    ZIO.succeed(if props.resetEpoch == model.resetEpoch then model else Model(0, props.resetEpoch))

  def handleMessage(props: Props, model: Model, ctx: MessageContext) =
    case Msg.Vote =>
      val updated = model.copy(votes = model.votes + 1)
      ctx.emit(Output.VoteChanged(props.id, updated.votes)).as(updated)
    case Msg.Reset =>
      ctx.emit(Output.VoteChanged(props.id, 0)).as(model.copy(votes = 0))

  def view(props: Signal[Props], model: Signal[Model], self: ComponentRef[Msg]) =
    articleTag(
      cls                        := "docs-vote-card",
      dataAttr("vote-component") := props.map(_.id),
      headerTag(
        div(
          p(cls := "docs-vote-kicker", "Component-local state"),
          h4(props.map(_.title))
        ),
        div(
          cls := "docs-vote-meta",
          code(dataAttr("component-id")   := props.map(_.id), props.map(_.id)),
          span(dataAttr("props-revision") := "", props.map(props => s"props r${props.revision}"))
        )
      ),
      p(cls := "docs-vote-description", props.map(_.description)),
      div(
        cls := "docs-vote-count-row",
        div(
          cls := "docs-vote-metric",
          span(dataAttr("vote-label")   := "", "Votes"),
          strong(dataAttr("vote-count") := "", model.map(_.votes.toString))
        ),
        div(
          cls := "docs-vote-actions",
          button(
            cls := "docs-vote-primary",
            typ := "button",
            phx.target(self),
            on.click.to(self)(Msg.Vote),
            "Vote"
          ),
          button(
            cls := "docs-vote-secondary",
            typ := "button",
            phx.target(self),
            on.click.to(self)(Msg.Reset),
            "Reset"
          )
        )
      )
    )
end VoteComponent

final class VotingComponentsExample
    extends LiveView[VotingComponentsExample.Msg, VotingComponentsExample.Model]:
  import VotingComponentsExample.*

  def mount(ctx: MountContext): Task[Model] = ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ComponentReported(id, votes) =>
      ZIO.succeed(
        model.copy(status = s"$id reported $votes vote${if votes == 1 then "" else "s"}.")
      )
    case Msg.UpdateScalaProps =>
      val revision = model.scalaRevision + 1
      ctx.components
        .sendUpdate(ScalaVote, scalaProps(revision, model.resetEpoch)).as(
          model
            .copy(scalaRevision = revision, status = s"Parent sent Scala props revision $revision.")
        )
    case Msg.Reset =>
      ZIO.succeed(Model.initial.copy(resetEpoch = model.resetEpoch + 1))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-voting-components",
      sectionTag(
        cls        := "docs-vote-parent",
        aria.label := "Parent LiveView state",
        div(
          p(cls := "docs-vote-kicker", "Parent-owned state"),
          p(
            dataAttr("vote-status") := "",
            role                    := "status",
            aria.live               := "polite",
            model.map(_.status)
          )
        ),
        button(typ := "button", on.click(Msg.UpdateScalaProps), "Parent updates Scala props")
      ),
      div(
        cls := "docs-vote-grid",
        ScalaVote.render(
          model.map(model => scalaProps(model.scalaRevision, model.resetEpoch)),
          outputToMessage
        ),
        ZioVote.render(
          model.map(model =>
            VoteComponent.Props(
              "zio-vote",
              "ZIO ecosystem",
              "A second stable instance proves that models and output attribution stay isolated.",
              0,
              model.resetEpoch
            )
          ),
          outputToMessage
        )
      )
    )

  private def outputToMessage(output: VoteComponent.Output): Msg = output match
    case VoteComponent.Output.VoteChanged(id, votes) => Msg.ComponentReported(id, votes)
end VotingComponentsExample

object VotingComponentsExample:
  final case class Model(scalaRevision: Int, resetEpoch: Int, status: String)

  object Model:
    val initial = Model(0, 0, "No component has reported a vote.")

  enum Msg:
    case ComponentReported(id: String, votes: Int)
    case UpdateScalaProps
    case Reset

  private val ScalaVote = component(VoteComponent, "scala-vote")
  private val ZioVote   = component(VoteComponent, "zio-vote")

  private def scalaProps(revision: Int, resetEpoch: Int) =
    VoteComponent.Props(
      "scala-vote",
      "Scala language",
      "Parent updates change these props while preserving the component's local vote count.",
      revision,
      resetEpoch
    )
// docs:end voting-components-example
