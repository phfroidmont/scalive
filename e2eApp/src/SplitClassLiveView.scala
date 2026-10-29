import zio.ZIO

import scalive.*

/** Rows whose class joins static and dynamic tokens, so that only the dynamic ones are sent. */
class SplitClassLiveView extends LiveView[SplitClassLiveView.Msg, SplitClassLiveView.Model]:
  import SplitClassLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(Vector(Entry(1, selected = true)), active = false))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add =>
      ZIO.succeed(
        model.copy(entries = model.entries :+ Entry(model.entries.size + 1, selected = false))
      )
    case Msg.Toggle(id) =>
      ZIO.succeed(
        model.copy(entries =
          model.entries.map(entry =>
            if entry.id == id then entry.copy(selected = !entry.selected) else entry
          )
        )
      )
    case Msg.Activate => ZIO.succeed(model.copy(active = !model.active))

  override def view(model: Signal[Model]) =
    div(
      button(idAttr := "add", on.click(Msg.Add), "Add"),
      button(idAttr := "activate", on.click(Msg.Activate), "Activate"),
      div(
        idAttr := "banner",
        cls    := model.map(model => if model.active then "active banner" else ""),
        cls    := "banner"
      ),
      ul(
        model.map(_.entries).splitBy(_.id) { (id, entry) =>
          li(
            idAttr := s"row-$id",
            cls    := "card big",
            cls    := entry.map(entry => if entry.selected then "selected" else ""),
            button(idAttr := s"toggle-$id", on.click(Msg.Toggle(id)), "Toggle")
          )
        }
      )
    )
end SplitClassLiveView

object SplitClassLiveView:
  final case class Entry(id: Int, selected: Boolean)
  final case class Model(entries: Vector[Entry], active: Boolean)

  enum Msg:
    case Add
    case Toggle(id: Int)
    case Activate
