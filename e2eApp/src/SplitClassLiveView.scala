import zio.ZIO

import scalive.*

/** Rows whose class joins static and dynamic tokens, so that only the dynamic ones are sent. */
class SplitClassLiveView extends LiveView[SplitClassLiveView.Msg, SplitClassLiveView.Model]:
  import SplitClassLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(Model(Vector(Row(1, selected = true)), active = false))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add =>
      ZIO.succeed(model.copy(rows = model.rows :+ Row(model.rows.size + 1, selected = false)))
    case Msg.Toggle(id) =>
      ZIO.succeed(
        model.copy(rows =
          model.rows.map(row => if row.id == id then row.copy(selected = !row.selected) else row)
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
        model.map(_.rows).splitBy(_.id) { (id, row) =>
          li(
            idAttr := s"row-$id",
            cls    := "card big",
            cls    := row.map(row => if row.selected then "selected" else ""),
            button(idAttr := s"toggle-$id", on.click(Msg.Toggle(id)), "Toggle")
          )
        }
      )
    )
end SplitClassLiveView

object SplitClassLiveView:
  final case class Row(id: Int, selected: Boolean)
  final case class Model(rows: Vector[Row], active: Boolean)

  enum Msg:
    case Add
    case Toggle(id: Int)
    case Activate
