import zio.ZIO

import scalive.*

/** Changes the page and a component nested in another, unchanged, component in one diff. */
class NestedComponentUpdateLiveView extends LiveView[Unit, Int]:
  import NestedComponentUpdateLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    _ =>
      val count = model + 1
      ctx.components.sendUpdate(component(Label, "nested"), s"nested $count").as(count)

  override def view(model: Signal[Int]) =
    div(
      button(idAttr := "bump", on.click(()), "Bump"),
      p(idAttr      := "count", model.map(_.toString)),
      component(Outer, "outer").render(())
    )

object NestedComponentUpdateLiveView:
  object Label extends LiveComponent[String, Nothing, String]:
    def mount(props: String, ctx: MountContext) = ZIO.succeed(props)

    override def update(props: String, model: String, ctx: UpdateContext) = ZIO.succeed(props)

    def handleMessage(props: String, model: String, ctx: MessageContext) = _ => ZIO.succeed(model)

    def view(props: Signal[String], model: Signal[String]) = span(model)

  object Outer extends LiveComponent[Unit, Nothing, Unit]:
    def mount(props: Unit, ctx: MountContext) = ZIO.unit

    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) = _ => ZIO.unit

    def view(props: Signal[Unit], model: Signal[Unit]) =
      div(idAttr := "outer", component(Label, "nested").render("nested 0"))
