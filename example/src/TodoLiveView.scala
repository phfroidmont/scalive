import TodoLiveView.*
import monocle.syntax.all.*
import scalive.*
import zio.*
import zio.stream.ZStream

class TodoLiveView() extends LiveView[Msg, Model]:

  def init = Model(List(Todo(99, "Buy eggs")))

  def update(model: Model) =
    case Msg.Add(text) =>
      val nextId = model.todos.maxByOption(_.id).map(_.id).getOrElse(1) + 1
      model
        .focus(_.todos)
        .modify(_.appended(Todo(nextId, text)))
    case Msg.Remove(id) =>
      model
        .focus(_.todos)
        .modify(_.filterNot(_.id == id))
    case Msg.ToggleCompletion(id) =>
      model
        .focus(_.todos)
        .modify(
          _.map(todo => if todo.id == id then todo.copy(completed = todo.completed) else todo)
        )

  def view(model: Dyn[Model]) =
    div(
      cls := "mx-auto card bg-base-100 max-w-2xl shadow-xl space-y-6 p-6",
      div(
        cls := "card-body",
        h1(cls := "card-title", "Todos"),
        form(
          cls := "flex items-center gap-3",
          phx.onSubmit(p => Msg.Add(p("todo-name"))),
          input(
            cls         := "input input-bordered grow",
            typ         := "text",
            nameAttr    := "todo-name",
            placeholder := "What needs to be done?",
            value       := model(_.inputText)
          )
        ),
        ul(
          cls := "divide-y divide-base-200",
          model(_.todos).splitBy(_.id)((id, todo) =>
            li(
              cls := "py-3 flex items-center gap-3",
              input(
                tpe     := "checkbox",
                cls     := "checkbox checkbox-primary",
                checked := todo(_.completed),
                phx.onClick(Msg.ToggleCompletion(id))
              ),
              todo(_.text),
              button(
                cls := "btn btn-ghost btn-sm text-error",
                phx.onClick(Msg.Remove(id)),
                "✕"
              )
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

  final case class Model(
    todos: List[Todo] = List.empty,
    inputText: String = "",
    filter: Filter = Filter.All)
  final case class Todo(id: Int, text: String, completed: Boolean = false)
  enum Filter:
    case All, Active, Completed
