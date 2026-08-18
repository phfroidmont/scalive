package scalive.docs.examples

import scalive.*

// docs:start activity-stream-example
final class ActivityStreamExample
    extends LiveView[ActivityStreamExample.Msg, ActivityStreamExample.Model]:
  import ActivityStreamExample.*

  def mount(ctx: MountContext): LiveIO[Model] =
    initialModel(ctx)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add =>
      val template = NewActivityTemplates((model.nextId - 1) % NewActivityTemplates.size)
      val activity = Activity(model.nextId, template._1, template._2)
      ctx.streams.insert(ActivityStreamDef, activity).map { stream =>
        model.copy(
          activities = model.activities :+ activity,
          activityStream = stream,
          nextId = model.nextId + 1
        )
      }
    case Msg.Delete(activity) =>
      ctx.streams.delete(ActivityStreamDef, activity).map { stream =>
        model.copy(
          activities = model.activities.filterNot(_.id == activity.id),
          activityStream = stream
        )
      }
    case Msg.Reset =>
      ctx.streams.reset(ActivityStreamDef, InitialActivities).map { stream =>
        Model(InitialActivities, stream, InitialNextId)
      }

  override def view(model: Signal[Model]): HtmlElement[Msg] =
    div(
      cls := "docs-activity-stream",
      sectionTag(
        cls        := "docs-activity-summary",
        aria.label := "Activity stream state",
        div(
          span(cls := "docs-activity-label", "Durable history"),
          strong(
            cls                        := "docs-activity-count",
            dataAttr("activity-count") := "",
            model.map(_.activities.size.toString)
          )
        ),
        p(
          span("DOM window"),
          "Five recent rows. Complete history remains in the model."
        )
      ),
      div(
        cls := "docs-activity-controls",
        button(
          typ                      := "button",
          dataAttr("add-activity") := "",
          on.click(Msg.Add),
          "Insert activity"
        )
      ),
      model
        .map(_.activityStream).renderIn(
          ol,
          cls        := "docs-activity-list",
          aria.label := "Recent activity"
        ) { activity =>
          li(
            dataAttr("activity-row") := "",
            div(
              cls := "docs-activity-row-content",
              span(cls  := "docs-activity-category", activity.map(_.category)),
              p(cls     := "docs-activity-message", activity.map(_.summary)),
              small(cls := "docs-activity-id", activity.map(value => s"Activity #${value.id}"))
            ),
            button(
              cls                         := "docs-activity-delete",
              typ                         := "button",
              dataAttr("delete-activity") := activity.map(_.id.toString),
              aria.label                  := activity.map(value => s"Delete activity ${value.id}"),
              on.click(activity.map(Msg.Delete(_))),
              "Delete"
            )
          )
        }
    )

  private def initialModel(ctx: MountContext): LiveIO[Model] =
    ctx.streams.create(ActivityStreamDef, InitialActivities).map { stream =>
      Model(InitialActivities, stream, InitialNextId)
    }
end ActivityStreamExample

object ActivityStreamExample:
  final case class Activity(id: Int, category: String, summary: String)

  final case class Model(
    activities: Vector[Activity],
    activityStream: LiveStream[Activity],
    nextId: Int)

  enum Msg:
    case Add
    case Delete(activity: Activity)
    case Reset

  private val ActivityStreamDef =
    LiveStreamDef.byId[Activity, Int]("activity")(_.id).keepLast(5)

  private val InitialActivities = Vector(
    Activity(1, "Navigation", "Opened the typed search example"),
    Activity(2, "Forms", "Validated a profile draft"),
    Activity(3, "Rendering", "Applied a keyed collection patch"),
    Activity(4, "Components", "Updated component props by stable ID")
  )

  private val InitialNextId = InitialActivities.map(_.id).max + 1

  private val NewActivityTemplates = Vector(
    "Streams"    -> "Inserted a bounded activity row",
    "Navigation" -> "Patched to another results page",
    "Components" -> "Updated component props by stable ID",
    "Async work" -> "Completed a deterministic report"
  )
end ActivityStreamExample
// docs:end activity-stream-example
