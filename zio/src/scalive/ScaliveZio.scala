package scalive

import zio.http.Response
import zio.http.template.Html

// trait LiveRouter:
//   type RootModel
//   private lazy val viewsMap: Map[String, View] = views.map(r => (r.name, r.view)).toMap
//   def rootLayout: HtmlElement[RootModel]
//   def views: Seq[LiveRoute]
//
// final case class LiveRoute(name: String, view: View)

object ZioLiveApp:
// 1 Request to live route
// 2 Create live view with stateless token containing user id if connected, http params, live view id
// 3 Response with HTML and token
// 4 Websocket connection with token
// 5 Recreate exact same liveview as before using token data

  // val testRoute = LiveRoute("test", TestView)
  // val router    = new LiveRouter:
  //   val rootLayout = htmlRootTag()
  //   val views      = Seq(testRoute)

  // def htmlRender(v: View, model: v.Model) =
  //   val lv = LiveView(v, model)
  //   Response.html(Html.raw(HtmlBuilder.build(lv, isRoot = true)))

  private val socketApp = ???
