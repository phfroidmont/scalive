import zio.ZIO

import scalive.*

/** Rows that put their key in their id, a data attribute, a branch and their text, which the
  * encoder sends with one shared template.
  */
class KeyDerivedRowsLiveView
    extends LiveView[KeyDerivedRowsLiveView.Msg, Vector[KeyDerivedRowsLiveView.Row]]:
  import KeyDerivedRowsLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(rows("a", "b", "c"))

  def handleMessage(model: Vector[Row], ctx: MessageContext) =
    case Msg.Add    => ZIO.succeed(model ++ rows("d", "e", "f"))
    case Msg.Rename =>
      ZIO.succeed(model.map(row => if row.key == "b" then row.copy(label = "renamed") else row))
    case Msg.Finish =>
      ZIO.succeed(model.map(row => if row.key == "e" then row.copy(done = true) else row))

  override def view(model: Signal[Vector[Row]]) =
    div(
      button(idAttr := "add", on.click(Msg.Add), "Add"),
      button(idAttr := "rename", on.click(Msg.Rename), "Rename"),
      button(idAttr := "finish", on.click(Msg.Finish), "Finish"),
      ul(
        idAttr := "rows",
        model.splitBy(_.key)((key, row) =>
          li(
            idAttr          := s"row-$key",
            dataAttr("key") := key,
            s"Row $key: ",
            span(cls := "label", row.map(_.label)),
            row
              .map(_.done).choose(
                em(idAttr := s"done-$key", "done"),
                em(idAttr := s"todo-$key", "todo")
              )
          )
        )
      )
    )
end KeyDerivedRowsLiveView

object KeyDerivedRowsLiveView:
  final case class Row(key: String, label: String, done: Boolean)

  enum Msg:
    case Add, Rename, Finish

  private def rows(keys: String*): Vector[Row] = keys.toVector.map(Row(_, "ready", done = false))
