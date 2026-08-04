package scalive.examples.services

import zio.*

import scalive.*

final class GuestbookLiveView(guestbook: Guestbook)
    extends LiveView[GuestbookLiveView.Msg, GuestbookLiveView.Model]:
  import GuestbookLiveView.*

  def mount(ctx: MountContext) =
    reload

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Add(author, message) => guestbook.add(author, message) *> reload
    case Msg.Reload               => reload

  def render(model: Model) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Services"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Shared guestbook service"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "ExamplesApp provides one Guestbook service layer. GuestbookLiveView.layer constructor-injects that shared service into each view. Each mutation reloads a fresh UI snapshot."
        )
      ),
      div(
        cls := "mb-6 flex flex-wrap gap-3",
        button(
          typ := "button",
          cls := "btn btn-primary",
          on.click(Msg.Add("Ada", "Typed services keep effects explicit.")),
          "Add Ada's note"
        ),
        button(
          typ := "button",
          cls := "btn btn-outline",
          on.click(Msg.Add("Grace", "Shared state is visible to every connection.")),
          "Add Grace's note"
        ),
        button(
          typ := "button",
          cls := "btn btn-ghost",
          on.click(Msg.Reload),
          "Reload shared entries"
        )
      ),
      div(
        cls := "space-y-3",
        model.entries.splitBy(_.id) { (_, entry) =>
          articleTag(
            cls := "rounded-box border border-base-300 bg-base-100 p-5 shadow-sm",
            div(
              cls := "mb-2 flex items-baseline justify-between gap-4",
              h2(cls   := "font-semibold", entry.author),
              span(cls := "font-mono text-xs text-base-content/45", s"#${entry.id}")
            ),
            p(cls := "leading-7 text-base-content/75", entry.message)
          )
        }
      )
    )

  private def reload =
    guestbook.entries.map(Model.apply)
end GuestbookLiveView

object GuestbookLiveView:
  val layer = ZLayer.fromFunction(GuestbookLiveView.apply)

  final case class Model(entries: Vector[Guestbook.Entry])

  enum Msg:
    case Add(author: String, message: String)
    case Reload
