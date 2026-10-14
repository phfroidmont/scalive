package hooks

import zio.*
import zio.json.ast.Json

import scalive.*

final case class User(name: String, canEdit: Boolean)

trait AuditLog:
  def record(user: User, action: String): Task[Unit]

object Board:
  final case class Model(user: User, cards: List[String])

  enum Msg:
    case AddCard(title: String)
    case ClearCards
    case StopAuditing

  def canSend(user: User, msg: Msg): Boolean =
    msg match
      case Msg.AddCard(_) | Msg.ClearCards => user.canEdit
      case Msg.StopAuditing                => true

  def nextModel(model: Model, msg: Msg): Model =
    msg match
      case Msg.AddCard(title) => model.copy(cards = model.cards :+ title)
      case Msg.ClearCards     => model.copy(cards = Nil)
      case Msg.StopAuditing   => model

  def boardView(model: Signal[Model]): HtmlElement[Msg] =
    div(
      p(model.map(_.cards.mkString(", "))),
      button(typ := "button", on.click(Msg.AddCard("New card")), "Add card"),
      button(typ := "button", on.click(Msg.ClearCards), "Clear cards"),
      button(typ := "button", on.click(Msg.StopAuditing), "Stop auditing")
    )

final class LoggedBoardLiveView(user: User) extends LiveView[Board.Msg, Board.Model]:
  import Board.*

  // docs:start hooks-static-liveview
  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks
      .empty[Msg, Model]
      .onEvent { (model, msg, _) =>
        ZIO.logDebug(s"browser message=$msg").as(LiveHookResult.cont(model))
      }
      .onInfo { (model, msg, _) =>
        ZIO.logDebug(s"server message=$msg").as(LiveHookResult.cont(model))
      }
  // docs:end hooks-static-liveview

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(user, Nil))

  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
    msg => ZIO.succeed(nextModel(model, msg))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    boardView(model)

final class AuthorizedBoardLiveView(user: User) extends LiveView[Board.Msg, Board.Model]:
  import Board.*

  // docs:start hooks-halt-event
  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.onEvent { (model, msg, _) =>
      if canSend(model.user, msg) then ZIO.succeed(LiveHookResult.cont(model))
      else ZIO.succeed(LiveHookResult.halt(model))
    }
  // docs:end hooks-halt-event

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(user, Nil))

  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
    msg => ZIO.succeed(nextModel(model, msg))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    boardView(model)

final class AuditedBoardLiveView(user: User, auditLog: AuditLog)
    extends LiveView[Board.Msg, Board.Model]:
  import Board.*

  // docs:start hooks-dynamic-root
  private val AuditHookId = "audit-events"

  def mount(ctx: MountContext): Task[Model] =
    ctx.hooks.event
      .attach(AuditHookId) { (model, msg, _) =>
        auditLog.record(model.user, msg.toString).as(LiveHookResult.cont(model))
      }
      .as(Model(user, Nil))

  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
    case Msg.StopAuditing =>
      ctx.hooks.event.detach(AuditHookId).as(model)
    case msg =>
      ZIO.succeed(nextModel(model, msg))
  // docs:end hooks-dynamic-root

  def view(model: Signal[Model]): HtmlElement[Msg] =
    boardView(model)

final class ProtectedBoardLiveView(user: User) extends LiveView[Board.Msg, Board.Model]:
  import Board.*

  // docs:start hooks-raw-event
  private val EditingEvents = Set("board:import", "board:reorder")

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.onRawEvent { (model, event, _) =>
      if !model.user.canEdit && EditingEvents.contains(event.bindingId) then
        ZIO.succeed(
          LiveEventHookResult.haltReply(model, Json.Obj("error" -> Json.Str("forbidden")))
        )
      else ZIO.succeed(LiveEventHookResult.cont(model))
    }
  // docs:end hooks-raw-event

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(user, Nil))

  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
    msg => ZIO.succeed(nextModel(model, msg))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    boardView(model)
