package scalive.examples.collections

import scalive.*

final class ActivityStreamLiveView
    extends LiveView[ActivityStreamLiveView.Msg, ActivityStreamLiveView.Model]:
  import ActivityStreamLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams
      .init(ActivityStreamDef, InitialActivities, limit = RecentLimit)
      .map(stream =>
        Model(
          activities = InitialActivities,
          activityStream = stream,
          nextId = InitialActivities.map(_.id).max + 1
        )
      )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add =>
      val template = NewActivityTemplates((model.nextId - 1) % NewActivityTemplates.size)
      val activity = Activity(model.nextId, template._1, template._2)
      ctx.streams
        .insert(
          ActivityStreamDef,
          activity,
          at = StreamAt.Last,
          limit = RecentLimit
        )
        .map(stream =>
          model.copy(
            activities = model.activities :+ activity,
            activityStream = stream,
            nextId = model.nextId + 1
          )
        )
    case Msg.Delete(activity) =>
      ctx.streams
        .delete(ActivityStreamDef, activity)
        .map(stream =>
          model.copy(
            activities = model.activities.filterNot(_.id == activity.id),
            activityStream = stream
          )
        )
    case Msg.ResetWindow =>
      ctx.streams
        .init(
          ActivityStreamDef,
          model.activities.takeRight(ResetWindowSize),
          reset = true,
          limit = RecentLimit
        )
        .map(stream => model.copy(activityStream = stream))
  end handleMessage

  def render(model: Model) =
    val categoryCounts = model.activities
      .groupMapReduce(_.category)(_ => 1)(_ + _)
      .toVector
      .sortBy(_._1)

    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Collections"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Bounded activity stream"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "The Vector is durable, queryable application state. The opaque LiveStream handle only describes efficient DOM updates and is replaced after every stream operation."
        )
      ),
      div(
        cls := "mb-6 grid gap-4 sm:grid-cols-2",
        div(
          cls := "rounded-box border border-base-300 bg-base-100 p-5",
          p(
            cls := "text-sm font-medium uppercase tracking-wide text-base-content/55",
            "Durable history"
          ),
          p(cls := "mt-2 text-3xl font-bold", model.activities.size.toString),
          p(
            cls := "mt-2 text-sm text-base-content/65",
            categoryCounts.map { case (category, count) => s"$category: $count" }.mkString(" | ")
          )
        ),
        div(
          cls := "rounded-box border border-base-300 bg-base-100 p-5",
          p(
            cls := "text-sm font-medium uppercase tracking-wide text-base-content/55",
            "Stream policy"
          ),
          p(cls := "mt-2 text-xl font-semibold", "Keep the latest 5 inserts"),
          p(
            cls := "mt-2 text-sm text-base-content/65",
            "Older activities remain in the Vector; stream internals are never read as application data."
          )
        )
      ),
      div(
        cls := "mb-5 flex flex-wrap gap-3",
        button(
          typ := "button",
          cls := "btn btn-primary",
          phx.onClick(Msg.Add),
          "Insert activity"
        ),
        button(
          typ := "button",
          cls := "btn btn-outline",
          phx.onClick(Msg.ResetWindow),
          "Reset stream to latest 3"
        )
      ),
      ol(
        idAttr       := "activity",
        phx.onUpdate := "stream",
        cls          := "grid gap-3",
        model.activityStream.stream { (domId, activity) =>
          li(
            idAttr := domId,
            cls := "flex items-start justify-between gap-4 rounded-box border border-base-300 bg-base-100 p-5 shadow-sm",
            div(
              div(cls := "badge badge-ghost badge-sm mb-2", activity.category),
              p(cls   := "font-medium", activity.summary),
              p(cls   := "mt-1 font-mono text-xs text-base-content/50", s"Activity #${activity.id}")
            ),
            button(
              typ := "button",
              cls := "btn btn-ghost btn-sm",
              phx.onClick(Msg.Delete(activity)),
              "Delete"
            )
          )
        }
      )
    )
  end render
end ActivityStreamLiveView

object ActivityStreamLiveView:
  final case class Activity(id: Int, category: String, summary: String)

  final case class Model(
    activities: Vector[Activity],
    activityStream: LiveStream[Activity],
    nextId: Int)

  enum Msg:
    case Add
    case Delete(activity: Activity)
    case ResetWindow

  private val ActivityStreamDef = LiveStreamDef.byId[Activity, Int]("activity")(_.id)
  private val RecentLimit       = Some(StreamLimit.KeepLast(5))
  private val ResetWindowSize   = 3

  private val InitialActivities = Vector(
    Activity(1, "Navigation", "Opened the typed search example"),
    Activity(2, "Forms", "Validated a profile draft"),
    Activity(3, "Uploads", "Stored a small Markdown document"),
    Activity(4, "Components", "Voted in a local component")
  )

  private val NewActivityTemplates = Vector(
    "Streams"    -> "Inserted a bounded activity row",
    "Navigation" -> "Patched to another results page",
    "Components" -> "Updated component props by stable ID",
    "Async work" -> "Completed a deterministic report"
  )
end ActivityStreamLiveView
