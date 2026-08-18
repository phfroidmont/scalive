package scalive

import java.net.URI

import zio.*
import zio.json.*
import zio.json.ast.Json

private[scalive] object StaticTracking:
  def collect(el: HtmlElement[?]): List[String] =
    RenderSnapshot.compile(el).trackedStaticUrls.toList

  def collectView[A](value: A)(view: Signal[A] => HtmlElement[?]): Task[List[String]] =
    val graph = ViewGraph.build(view)
    graph
      .evaluateZIO(
        value,
        SignalEvaluation.empty,
        revision = 1L,
        StaticResolver
      ).map(_.compiled.trackedStaticUrls.toList).ensuring(ZIO.succeed(graph.dispose()))

  def clientListFromParams(params: Option[Map[String, Json]]): Option[List[String]] =
    params.flatMap(_.get("_track_static")).flatMap(_.as[List[String]].toOption)

  def staticChanged(client: Option[List[String]], server: List[String]): Boolean =
    client.exists(urls => urls.nonEmpty && urls.map(normalizeUrl) != server.map(normalizeUrl))

  private def normalizeUrl(value: String): String =
    val withoutQuery = value.takeWhile(ch => ch != '?' && ch != '#')
    try
      val uri = URI.create(value)
      Option(uri.getRawPath).filter(_.nonEmpty).getOrElse(withoutQuery)
    catch case _: IllegalArgumentException => withoutQuery

  private object StaticResolver extends ViewGraph.Resolver:
    private val empty = ViewGraph.ResolvedContent(
      RenderSnapshot.StringSlot(""),
      Map.empty,
      Vector.empty
    )

    def component(
      spec: LiveComponentSpec[?, ?, ?, ?],
      path: BindingId.Path,
      transaction: SignalEvaluation.Transaction
    ): Task[ViewGraph.ResolvedContent] =
      ZIO.succeed(empty)

    def liveView(
      spec: NestedLiveViewSpec[?, ?],
      path: BindingId.Path
    ): Task[ViewGraph.ResolvedContent] =
      ZIO.succeed(empty)

    def flash(kind: String): Task[Option[String]] = ZIO.none
end StaticTracking
