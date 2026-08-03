package scalive.examples.components

import zio.ZIO

import scalive.*

object VoteComponent
    extends LiveComponent[VoteComponent.Props, VoteComponent.Msg, VoteComponent.Model]:
  final case class Props(title: String, description: String)

  final case class Model(votes: Int)

  enum Msg:
    case Vote
    case Reset

  def mount(props: Props, ctx: MountContext) =
    ZIO.succeed(Model(votes = 0))

  def handleMessage(props: Props, model: Model, ctx: MessageContext) =
    case Msg.Vote  => ZIO.succeed(model.copy(votes = model.votes + 1))
    case Msg.Reset => ZIO.succeed(model.copy(votes = 0))

  def render(props: Props, model: Model, self: ComponentRef[Msg]) =
    articleTag(
      cls := "rounded-box border border-base-300 bg-base-100 p-6 shadow-sm",
      p(cls := "text-sm font-medium uppercase tracking-wide text-primary", "Local component state"),
      h2(cls := "mt-2 text-2xl font-bold", props.title),
      p(cls  := "mt-2 min-h-12 leading-6 text-base-content/65", props.description),
      div(
        cls := "my-6 flex items-end justify-between gap-4",
        div(
          p(cls := "text-sm text-base-content/55", "Votes"),
          p(cls := "font-mono text-5xl font-bold", model.votes.toString)
        ),
        div(
          cls := "flex gap-2",
          button(
            typ := "button",
            cls := "btn btn-primary",
            phx.onClick(Msg.Vote),
            phx.target(self),
            "Vote"
          ),
          button(
            typ := "button",
            cls := "btn btn-ghost",
            phx.onClick(Msg.Reset),
            phx.target(self),
            "Reset"
          )
        )
      )
    )
end VoteComponent
