import NavigationLiveViews.*
import zio.http.URL
import zio.schema.derived
import zio.schema.Schema
import zio.stream.ZStream

import scalive.*

class NavigationALiveView() extends LiveView[Msg, Model]:

  override val queryCodec: LiveQueryCodec[AParams] = AParamsCodec

  def mount = Model(paramCurrent = None, paramNext = 1)

  override def handleParams(model: Model, params: AParams, _url: URL) =
    model.copy(paramCurrent = params.param.map(_.toString))

  def handleMessage(model: Model) =
    case _ => model

  def render(model: Model) =
    NavigationLayout(
      div(
        h1("This is page A"),
        p("Current param: ", model.paramCurrent.getOrElse("")),
        link.patch(
          AParamsCodec,
          AParams(Some(model.paramNext)),
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch this LiveView"
        ),
        link.patchReplace(
          AParamsCodec,
          AParams(Some(model.paramNext)),
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch (Replace)"
        ),
        link.navigate(
          "/navigation/b#items-item-42",
          cls := "inline-flex rounded bg-slate-200 px-4 py-2",
          "Navigate to 42"
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end NavigationALiveView

class NavigationBLiveView() extends LiveView[Msg, Model]:

  override val queryCodec: LiveQueryCodec[BParams] = BParamsCodec

  def mount =
    Model(items = (1 to 100).toList.map(i => Item(s"item-$i", i)), withContainer = false)

  def handleMessage(model: Model) =
    case Msg.Noop => model
    case _        => model

  override def handleParams(model: Model, params: BParams, url: URL) =
    val containerFlow = params.withContainerRequested || model.withContainer
    val selectedItem  = if containerFlow then selectedItemFromPath(url) else None
    model.copy(
      withContainer = params.withContainerRequested,
      selectedItem = selectedItem
    )

  def render(model: Model) =
    NavigationLayout(
      div(
        h1("This is page B"),
        a(
          href := "#items-item-42",
          cls  := "mb-2 inline-flex rounded bg-slate-200 px-4 py-2",
          "Go to 42."
        ),
        if model.selectedItem.isEmpty then
          div(
            idAttr    := "my-scroll-container",
            styleAttr :=
              (if model.withContainer then
                 "height: 85vh; overflow-y: scroll; width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"
               else "width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"),
            ul(
              idAttr    := "items",
              styleAttr := "padding: 1rem; list-style: none;",
              model.items.splitBy(_.id) { (_, item) =>
                li(
                  idAttr    := s"items-${item.id}",
                  styleAttr := "padding: 0.5rem; border-bottom: 1px solid #e2e8f0;",
                  link.patch(
                    itemHref(item.id, model.withContainer),
                    "Item ",
                    item.name.toString
                  )
                )
              }
            )
          )
        else "",
        if model.selectedItem.nonEmpty then
          div(
            p("Item ", model.selectedItem.getOrElse(""))
          )
        else ""
      )
    )

  def subscriptions(model: Model) = ZStream.empty

  private def selectedItemFromPath(url: URL): Option[String] =
    url.path.segments.toList match
      case "navigation" :: "b" :: id :: Nil if id.nonEmpty => Some(id)
      case _                                               => None

  private def itemHref(id: String, withContainer: Boolean): String =
    val base = s"/navigation/b/$id"
    if withContainer then s"$base?container=1" else base
end NavigationBLiveView

class RedirectLoopLiveView() extends LiveView[Msg, Model]:

  override val queryCodec: LiveQueryCodec[RedirectLoopParams] = RedirectLoopParamsCodec

  def mount =
    Model(shouldLoop = false, message = None)

  def handleMessage(model: Model) =
    case Msg.TriggerLoop => model.copy(message = Some("Too many redirects"), shouldLoop = false)
    case _               => model

  override def handleParams(model: Model, params: RedirectLoopParams, _url: URL) =
    if params.loop.contains(true) then
      if model.shouldLoop then
        LiveContext.pushPatch(RedirectLoopParamsCodec, RedirectLoopParams(Some(true))).as(model)
      else model.copy(message = Some("Too many redirects"), shouldLoop = false)
    else model.copy(message = None, shouldLoop = true)

  def render(model: Model) =
    NavigationLayout(
      div(
        if model.message.nonEmpty then
          div(
            idAttr := "message",
            model.message.getOrElse("")
          )
        else "",
        link.patch(
          RedirectLoopParamsCodec,
          RedirectLoopParams(Some(true)),
          "Redirect Loop"
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end RedirectLoopLiveView

object NavigationLiveViews:

  enum Msg:
    case TriggerLoop
    case Noop

  final case class AParams(param: Option[Int]) derives Schema

  val AParamsCodec: LiveQueryCodec[AParams] =
    LiveQueryCodec[AParams]

  final case class BParams(withContainerRequested: Boolean)

  val BParamsCodec: LiveQueryCodec[BParams] =
    LiveQueryCodec.custom(
      decodeFn =
        url => Right(BParams(withContainerRequested = url.queryParam("container").contains("1"))),
      encodeFn = params =>
        Right(
          if params.withContainerRequested then "?container=1"
          else "?"
        )
    )

  final case class RedirectLoopParams(loop: Option[Boolean]) derives Schema

  val RedirectLoopParamsCodec: LiveQueryCodec[RedirectLoopParams] =
    LiveQueryCodec[RedirectLoopParams]

  final case class Item(id: String, name: Int)
  final case class Model(
    paramCurrent: Option[String] = None,
    paramNext: Int = 1,
    items: List[Item] = Nil,
    selectedItem: Option[String] = None,
    withContainer: Boolean = false,
    shouldLoop: Boolean = false,
    message: Option[String] = None)
end NavigationLiveViews
