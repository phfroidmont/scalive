import TodoLiveView.*
import monocle.syntax.all.*
import scalive.*
import zio.*
import zio.stream.ZStream

class TodoLiveView() extends LiveView[Msg, Model]:

  def init = ZIO.succeed(Model(List(Todo(99, "some task"))))

  def update(model: Model) =
    case Msg.Add(text) =>
      val nextId = model.todos.maxByOption(_.id).map(_.id).getOrElse(1)
      ZIO.succeed(
        model
          .focus(_.todos)
          .modify(_.appended(Todo(nextId, text)))
      )
    case Msg.Remove(id) =>
      ZIO.succeed(
        model
          .focus(_.todos)
          .modify(_.filterNot(_.id == id))
      )
    case Msg.ToggleCompletion(id) =>
      ZIO.succeed(
        model
          .focus(_.todos)
          .modify(
            _.map(todo => if todo.id == id then todo.copy(completed = todo.completed) else todo)
          )
      )

  def view(model: Dyn[Model]) =
    div(
      cls := "mx-auto card bg-base-100 max-w-2xl shadow-xl space-y-6 p-6",
      div(
        cls := "card-body",
        h1(cls := "card-title", "Todos"),
        div(
          cls := "flex items-center gap-3",
          input(
            cls         := "input input-bordered grow",
            typ         := "text",
            nameAttr    := "todo-text",
            placeholder := "What needs to be done?",
            phx.onKeyup.withValue(Msg.Add(_)),
            phx.key           := "Enter",
            phx.value("test") := "some value"
          )
        ),
        form(
          cls := "flex items-center gap-3",
          phx.onChange(p => Msg.Add(p("todo-text"))),
          phx.onSubmit(p => Msg.Add(p("todo-text"))),
          input(
            cls         := "input input-bordered grow",
            typ         := "text",
            nameAttr    := "todo-text",
            placeholder := "What needs to be done?"
          )
        ),
        ul(
          cls := "divide-y divide-base-200",
          model(_.todos).splitByIndex((_, elem) =>
            li(
              cls := "py-3 flex flex-wrap items-center justify-between gap-2",
              elem(_.text)
            )
          )
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end TodoLiveView

object TodoLiveView:

  enum Msg:
    case Add(text: String)
    case Remove(id: Int)
    case ToggleCompletion(id: Int)

  final case class Model(todos: List[Todo] = List.empty, filter: Filter = Filter.All)
  final case class Todo(id: Int, text: String, completed: Boolean = false)
  enum Filter:
    case All, Active, Completed
