import zio.ZIO

import scalive.*

/** Rows that put their key in their id, a data attribute, a branch and their text, which the
  * encoder sends with one shared template.
  */
class KeyDerivedRowsLiveView
    extends LiveView[KeyDerivedRowsLiveView.Msg, Vector[KeyDerivedRowsLiveView.Entry]]:
  import KeyDerivedRowsLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(entries("a", "b", "c"))

  def handleMessage(model: Vector[Entry], ctx: MessageContext) =
    case Msg.Add    => ZIO.succeed(model ++ entries("d", "e", "f"))
    case Msg.Rename =>
      ZIO.succeed(
        model.map(entry => if entry.key == "b" then entry.copy(label = "renamed") else entry)
      )
    case Msg.Finish =>
      ZIO.succeed(model.map(entry => if entry.key == "e" then entry.copy(done = true) else entry))

  override def view(model: Signal[Vector[Entry]]) =
    div(
      button(idAttr := "add", on.click(Msg.Add), "Add"),
      button(idAttr := "rename", on.click(Msg.Rename), "Rename"),
      button(idAttr := "finish", on.click(Msg.Finish), "Finish"),
      ul(
        idAttr := "rows",
        model.splitBy(_.key)((key, entry) =>
          li(
            idAttr          := s"row-$key",
            dataAttr("key") := key,
            s"Row $key: ",
            span(cls := "label", entry.map(_.label)),
            entry
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
  final case class Entry(key: String, label: String, done: Boolean)

  enum Msg:
    case Add, Rename, Finish

  private def entries(keys: String*): Vector[Entry] =
    keys.toVector.map(Entry(_, "ready", done = false))
