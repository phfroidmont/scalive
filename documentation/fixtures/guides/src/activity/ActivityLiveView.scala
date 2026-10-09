// docs:start streams-liveview
package activity

import zio.*

import scalive.*

// docs:start streams-model
final case class Activity(id: Int, summary: String)

final case class Model(
  activities: Vector[Activity],
  activityStream: LiveStream[Activity],
  nextId: Int)
// docs:end streams-model

enum Msg:
  case Add
  case Delete(activityId: Int)
  case Reset

// docs:start streams-definition
val ActivityStreamDef =
  LiveStreamDef.byId[Activity, Int]("activity")(_.id).keepLast(5)
// docs:end streams-definition

val InitialActivities = Vector(
  Activity(1, "Opened the search page"),
  Activity(2, "Validated a profile draft")
)

final class ActivityLiveView extends LiveView[Msg, Model]:
  // docs:start streams-mount
  def mount(ctx: MountContext): Task[Model] =
    ctx.streams.create(ActivityStreamDef, InitialActivities).map { stream =>
      Model(InitialActivities, stream, nextId = 3)
    }
  // docs:end streams-mount

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start streams-insert
    case Msg.Add =>
      val activity = Activity(model.nextId, s"Activity ${model.nextId}")
      ctx.streams.insert(ActivityStreamDef, activity).map { stream =>
        model.copy(
          activities = model.activities :+ activity,
          activityStream = stream,
          nextId = model.nextId + 1
        )
      }
    // docs:end streams-insert
    // docs:start streams-delete
    case Msg.Delete(activityId) =>
      ctx.streams.delete(ActivityStreamDef, activityId).map { stream =>
        model.copy(
          activities = model.activities.filterNot(_.id == activityId),
          activityStream = stream
        )
      }
    // docs:end streams-delete
    // docs:start streams-reset
    case Msg.Reset =>
      ctx.streams.reset(ActivityStreamDef, InitialActivities).map { stream =>
        Model(InitialActivities, stream, nextId = 3)
      }
    // docs:end streams-reset

  // docs:start streams-render
  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      p(model.map(current => s"${current.activities.size} activities")),
      button(typ := "button", on.click(Msg.Add), "Add activity"),
      button(typ := "button", on.click(Msg.Reset), "Reset"),
      model.map(_.activityStream).renderIn(ol, aria.label := "Recent activity") { activity =>
        li(
          p(activity.map(_.summary)),
          button(
            typ := "button",
            on.click(activity.map(value => Msg.Delete(value.id))),
            "Delete"
          )
        )
      }
    )
  // docs:end streams-render
end ActivityLiveView
// docs:end streams-liveview
