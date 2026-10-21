import zio.ZIO
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*

/** A dirty page with a patch link and a navigation link, guarded with either scope. */
class NavigationGuardScopeLiveView(scope: NavigationGuardScope, path: String)
    extends LiveView.Routed[
      Boolean,
      NavigationGuardScopeLiveView.Model,
      NavigationGuardScopeLiveView.UrlParams
    ]:
  import NavigationGuardScopeLiveView.*

  def mount(params: UrlParams, ctx: MountContext) = ZIO.succeed(Model(dirty = false, tab = "one"))

  override def handleParams(model: Model, params: UrlParams, url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(tab = params.tab.getOrElse("one")))

  def handleMessage(model: Model, ctx: MessageContext) =
    dirty => ZIO.succeed(model.copy(dirty = dirty))

  override def view(model: Signal[Model]) =
    div(
      navigation.guardWhen(model.map(_.dirty), "Leave without saving?", scope),
      button(idAttr := "edit", on.click(true), "Edit"),
      p(idAttr      := "tab", model.map(_.tab)),
      p(idAttr      := "dirty", model.map(_.dirty.toString)),
      link.pushPatchUnsafe(s"$path?tab=two", "Tab two"),
      link.pushNavigateUnsafe("/form-unsaved/target", "Leave")
    )

object NavigationGuardScopeLiveView:
  final case class UrlParams(tab: Option[String]) derives Schema

  final case class Model(dirty: Boolean, tab: String)
