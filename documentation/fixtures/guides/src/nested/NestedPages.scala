package nested

import zio.*

import scalive.*

final class SupportChatLiveView(conversationId: String)
    extends LiveView.Eventless[SupportChatLiveView.Model]:
  def mount(ctx: MountContext): Task[SupportChatLiveView.Model] =
    ZIO.succeed(SupportChatLiveView.Model(conversationId))

  def view(model: Signal[SupportChatLiveView.Model]): HtmlElement[Nothing] =
    asideTag(p("Conversation ", model.map(_.conversationId)))

object SupportChatLiveView:
  final case class Model(conversationId: String)

final class SupportPageLiveView(conversationId: String)
    extends LiveView.Eventless[SupportPageLiveView.Model]:
  import SupportPageLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(title = "Support"))

  // docs:start nested-sticky-child
  def view(model: Signal[Model]) =
    sectionTag(
      h1(model.map(_.title)),
      liveView(
        id = "support-chat",
        value = SupportChatLiveView(conversationId),
        sticky = true
      )
    )
  // docs:end nested-sticky-child

object SupportPageLiveView:
  final case class Model(title: String)

final class RequiredEditorLiveView(documentId: String)
    extends LiveView.Eventless[RequiredEditorLiveView.Model]:
  def mount(ctx: MountContext): Task[RequiredEditorLiveView.Model] =
    ZIO.succeed(RequiredEditorLiveView.Model(documentId))

  def view(model: Signal[RequiredEditorLiveView.Model]): HtmlElement[Nothing] =
    div(p("Editing ", model.map(_.documentId)))

object RequiredEditorLiveView:
  final case class Model(documentId: String)

final class DocumentPageLiveView(documentId: String)
    extends LiveView.Eventless[DocumentPageLiveView.Model]:
  import DocumentPageLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(title = "Document"))

  def view(model: Signal[Model]) =
    mainTag(
      h1(model.map(_.title)),
      // docs:start nested-linked-child
      liveView(
        id = "required-editor",
        value = RequiredEditorLiveView(documentId),
        linkParentOnCrash = true
      )
      // docs:end nested-linked-child
    )

object DocumentPageLiveView:
  final case class Model(title: String)
