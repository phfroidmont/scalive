import TodoLiveView.*
import zio.*
import zio.stream.ZStream

import scalive.*

class TodoLiveView() extends LiveView[Msg, Model]:

  def init = Model(
    List(
      Todo(99, "Buy eggs"),
      Todo(1, "Wash dishes", true)
    )
  )

  def update(model: Model) =
    case Msg.Add(text) =>
      val nextId = model.items.maxByOption(_.id).map(_.id).getOrElse(1) + 1
      model.copy(items = model.items.appended(Todo(nextId, text)))
    case Msg.Edit(id) =>
      model.updateItem(id, _.copy(editing = true))
    case Msg.Update(id, text) =>
      model.updateItem(id, _.copy(text = text, editing = false))
    case Msg.Remove(id) =>
      model.copy(items = model.items.filterNot(_.id == id))
    case Msg.ToggleCompletion(id) =>
      model.updateItem(id, todo => todo.copy(completed = !todo.completed))
    case Msg.SetFilter(filter) =>
      model.copy(filter = filter)
    case Msg.RemoveCompleted =>
      model.copy(items = model.items.filterNot(_.completed))

  def view(model: Dyn[Model]) =
    div(
      cls := "mx-auto card bg-base-100 max-w-2xl shadow-xl space-y-6 p-6",
      div(
        cls := "card-body",
        h1(cls := "card-title text-3xl", "Todos"),
        form(
          cls := "mt-6 flex items-center gap-3",
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
          model(_.filteredItems).splitBy(_.id)((id, todo) =>
            li(
              cls := "py-3 flex items-center gap-3",
              input(
                tpe     := "checkbox",
                cls     := "checkbox checkbox-primary",
                checked := todo(_.completed),
                phx.onClick(Msg.ToggleCompletion(id))
              ),
              todo.whenNot(_.editing)(
                div(
                  cls := "truncate cursor-text w-full",
                  phx.onClick(Msg.Edit(id)),
                  span(
                    cls := todo(t => if t.completed then "line-through opacity-60" else ""),
                    todo(_.text)
                  )
                )
              ),
              todo.when(_.editing)(
                input(
                  tpe   := "text",
                  cls   := "input input-bordered w-full",
                  value := todo(_.text),
                  phx.onMounted(JS.focus()),
                  phx.onBlur.withValue(Msg.Update(id, _))
                )
              ),
              button(
                cls := "btn btn-ghost btn-sm text-error",
                phx.onClick(Msg.Remove(id)),
                "✕"
              )
            )
          )
        ),
        div(
          cls := "card-actions flex flex-wrap items-center gap-3 justify-between",
          div(
            cls := "join",
            button(
              cls := model(_.filter match
                case Filter.All => "btn btn-sm join-item btn-active"
                case _          => "btn btn-sm join-item"),
              phx.onClick(Msg.SetFilter(Filter.All)),
              "All"
            ),
            button(
              cls := model(_.filter match
                case Filter.Active => "btn btn-sm join-item btn-active"
                case _             => "btn btn-sm join-item"),
              phx.onClick(Msg.SetFilter(Filter.Active)),
              "Active"
            ),
            button(
              cls := model(_.filter match
                case Filter.Completed => "btn btn-sm join-item btn-active"
                case _                => "btn btn-sm join-item"),
              phx.onClick(Msg.SetFilter(Filter.Completed)),
              "Completed"
            )
          ),
          span(
            cls := "badge badge-outline",
            model(m => s"${m.itemsLeft} item${if m.itemsLeft == 1 then "" else "s"} left")
          ),
          button(
            cls      := "btn btn-sm btn-outline",
            disabled := model(_.completedCount == 0),
            phx.onClick(Msg.RemoveCompleted),
            model(m => s"Clear completed (${m.completedCount})")
          )
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end TodoLiveView

object TodoLiveView:

  enum Msg:
    case Add(text: String)
    case Edit(id: Int)
    case Update(id: Int, text: String)
    case Remove(id: Int)
    case ToggleCompletion(id: Int)
    case SetFilter(value: Filter)
    case RemoveCompleted

  final case class Model(
    items: List[Todo] = List.empty,
    inputText: String = "",
    filter: Filter = Filter.All):
    def itemsLeft      = items.count(!_.completed)
    def completedCount = items.count(_.completed)
    def filteredItems  = items.filter(item =>
      filter match
        case Filter.All       => true
        case Filter.Active    => !item.completed
        case Filter.Completed => item.completed
    )
    def updateItem(id: Int, f: Todo => Todo) =
      import monocle.syntax.all.*
      this
        .focus(_.items).each.filter(_.id == id)
        .modify(f)

  final case class Todo(id: Int, text: String, completed: Boolean = false, editing: Boolean = false)

  enum Filter:
    case All, Active, Completed
end TodoLiveView
