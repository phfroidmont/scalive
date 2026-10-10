package hooks

import zio.*

import scalive.*

object CardComponent
    extends LiveComponent[CardComponent.Props, CardComponent.Msg, CardComponent.Model]:
  final case class Props(id: String, title: String, user: User, readOnly: Boolean)

  final case class Model(title: String, expanded: Boolean)

  enum Msg:
    case Rename(title: String)
    case ToggleDetails

  private def edits(msg: Msg): Boolean =
    msg match
      case Msg.Rename(_)     => true
      case Msg.ToggleDetails => false

  // docs:start hooks-static-component
  override def hooks: ComponentLiveHooks[Props, Msg, Model] =
    ComponentLiveHooks
      .empty[Props, Msg, Model]
      .onEvent { (props, model, msg, _) =>
        ZIO
          .logDebug(s"${props.user.name} sent $msg to card ${props.id}")
          .as(LiveHookResult.cont(model))
      }
      .afterRender { (props, model, _) =>
        ZIO.logDebug(s"card ${props.id} rendered '${model.title}'")
      }
  // docs:end hooks-static-component

  // docs:start hooks-dynamic-component
  def mount(props: Props, ctx: MountContext): Task[Model] =
    ctx.hooks.event
      .attach("read-only-policy") { (currentProps, model, msg, _) =>
        if currentProps.readOnly && edits(msg) then ZIO.succeed(LiveHookResult.halt(model))
        else ZIO.succeed(LiveHookResult.cont(model))
      }
      .as(Model(props.title, expanded = false))
  // docs:end hooks-dynamic-component

  def handleMessage(props: Props, model: Model, ctx: MessageContext): Msg => Task[Model] =
    case Msg.Rename(title) => ZIO.succeed(model.copy(title = title))
    case Msg.ToggleDetails => ZIO.succeed(model.copy(expanded = !model.expanded))

  def view(props: Signal[Props], model: Signal[Model], self: ComponentRef[Msg]) =
    articleTag(
      strong(model.map(_.title)),
      button(
        typ := "button",
        phx.target(self),
        on.click.to(self)(Msg.ToggleDetails),
        "Details"
      ),
      button(
        typ := "button",
        phx.target(self),
        on.click.to(self)(Msg.Rename("Renamed")),
        "Rename"
      )
    )
end CardComponent
