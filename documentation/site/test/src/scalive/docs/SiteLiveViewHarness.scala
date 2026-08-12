package scalive.docs

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.{Meta, Payload}

private[docs] final class SiteLiveViewHarness[Msg, Model] private (
  channel: LiveChannel,
  socket: Socket[Msg, Model],
  serverMessages: Queue[Msg],
  outputQueue: Queue[(Payload, Meta)],
  outputRef: Ref[Vector[(Payload, Meta)]],
  nextMessageRef: Ref[Int],
  val topic: String):

  def html: UIO[String] = socket.renderedHtml

  def text(selector: String): Task[String] =
    html.flatMap(value => ZIO.attempt(SiteLiveViewHarnessDom.selectOne(value, selector).text()))

  def click(selector: String): Task[Unit] =
    dispatchClick(SiteLiveViewHarnessDom.selectOne(_, selector))

  def clickButton(label: String): Task[Unit] =
    dispatchClick(SiteLiveViewHarnessDom.buttonByLabel(_, label))

  def changeForm(
    selector: String,
    fields: Vector[(String, String)],
    target: Option[String] = None
  ): Task[Unit] =
    dispatchForm(selector, "phx-change", fields, target)

  def submitForm(selector: String, fields: Vector[(String, String)]): Task[Unit] =
    dispatchForm(selector, "phx-submit", fields, None)

  private def dispatchClick(findElement: String => Element): Task[Unit] =
    for
      current <- html
      binding <- ZIO.attempt {
                   val element = findElement(current)
                   if !element.hasAttr("phx-click") then
                     throw new IllegalArgumentException("Element has no phx-click binding.")
                   element.attr("phx-click")
                 }
      messageRef <- nextMessageRef.updateAndGet(_ + 1)
      meta = Meta(
               joinRef = Some(1),
               messageRef = Some(messageRef),
               topic = topic,
               eventType = WebSocketMessage.Protocol.EventEvent
             )
      _ <- channel.event(
             topic,
             Payload.Event(`type` = "click", event = binding, value = Json.Obj.empty),
             meta
           )
      _ <- takeOutput { case (payload, outputMeta) =>
             outputMeta.messageRef.contains(messageRef) && payload.isInstanceOf[Payload.Reply]
           }
    yield ()

  private def dispatchForm(
    selector: String,
    attribute: String,
    fields: Vector[(String, String)],
    target: Option[String]
  ): Task[Unit] =
    for
      current <- html
      binding <- ZIO.attempt(SiteLiveViewHarnessDom.formBinding(current, selector, attribute))
      messageRef <- nextMessageRef.updateAndGet(_ + 1)
      meta = Meta(
               joinRef = Some(1),
               messageRef = Some(messageRef),
               topic = topic,
               eventType = WebSocketMessage.Protocol.EventEvent
             )
      eventMeta = target.map(value => Json.Obj("_target" -> Json.Str(value)))
      _ <- channel.event(
             topic,
             Payload.Event(
               `type` = "form",
               event = binding,
               value = Json.Str(SiteLiveViewHarnessDom.urlEncoded(fields)),
               meta = eventMeta
             ),
             meta
           )
      _ <- takeOutput { case (payload, outputMeta) =>
             outputMeta.messageRef.contains(messageRef) && payload.isInstanceOf[Payload.Reply]
           }
    yield ()

  def sendServer(message: Msg): Task[Unit] =
    serverMessages.offer(message) *>
      takeOutput { case (payload, _) => payload.isInstanceOf[Payload.Diff] }.unit

  def outputs: UIO[Vector[(Payload, Meta)]] = outputRef.get

  def joinNested(instanceId: String): RIO[Scope, SiteNestedLiveViewHarness] =
    SiteNestedLiveViewHarness.join(channel, instanceId)

  def socketExists(socketTopic: String): UIO[Boolean] =
    channel.socket(socketTopic).map(_.nonEmpty)

  def leave: UIO[Unit] = channel.leave(topic)

  private def takeOutput(predicate: ((Payload, Meta)) => Boolean): Task[(Payload, Meta)] =
    outputQueue.take
      .repeatUntil(predicate)
      .timeoutFail(new RuntimeException("Timed out waiting for LiveView output"))(3.seconds)
end SiteLiveViewHarness

private[docs] final class SiteNestedLiveViewHarness private (
  channel: LiveChannel,
  socket: Socket[?, ?],
  outputQueue: Queue[(Payload, Meta)],
  nextMessageRef: Ref[Int],
  val topic: String):

  def html: UIO[String] = socket.renderedHtml

  def text(selector: String): Task[String] =
    html.flatMap(value => ZIO.attempt(SiteLiveViewHarnessDom.selectOne(value, selector).text()))

  def click(selector: String): Task[Unit] =
    dispatchClick(SiteLiveViewHarnessDom.selectOne(_, selector))

  def clickButton(label: String): Task[Unit] =
    dispatchClick(SiteLiveViewHarnessDom.buttonByLabel(_, label))

  def changeForm(
    selector: String,
    fields: Vector[(String, String)],
    target: Option[String] = None
  ): Task[Unit] =
    dispatchForm(selector, "phx-change", fields, target)

  def submitForm(selector: String, fields: Vector[(String, String)]): Task[Unit] =
    dispatchForm(selector, "phx-submit", fields, None)

  private def dispatchClick(findElement: String => Element): Task[Unit] =
    for
      current <- html
      binding <- ZIO.attempt {
                   val element = findElement(current)
                   if !element.hasAttr("phx-click") then
                     throw new IllegalArgumentException("Element has no phx-click binding.")
                   element.attr("phx-click")
                 }
      messageRef <- nextMessageRef.updateAndGet(_ + 1)
      meta = Meta(
               joinRef = Some(2),
               messageRef = Some(messageRef),
               topic = topic,
               eventType = WebSocketMessage.Protocol.EventEvent
             )
      _ <- channel.event(
             topic,
             Payload.Event(`type` = "click", event = binding, value = Json.Obj.empty),
             meta
           )
      _ <- outputQueue.take
             .repeatUntil { case (payload, outputMeta) =>
               outputMeta.messageRef.contains(messageRef) && payload.isInstanceOf[Payload.Reply]
             }
             .timeoutFail(new RuntimeException("Timed out waiting for nested LiveView output"))(3.seconds)
    yield ()

  private def dispatchForm(
    selector: String,
    attribute: String,
    fields: Vector[(String, String)],
    target: Option[String]
  ): Task[Unit] =
    for
      current <- html
      binding <- ZIO.attempt(SiteLiveViewHarnessDom.formBinding(current, selector, attribute))
      messageRef <- nextMessageRef.updateAndGet(_ + 1)
      meta = Meta(
               joinRef = Some(2),
               messageRef = Some(messageRef),
               topic = topic,
               eventType = WebSocketMessage.Protocol.EventEvent
             )
      eventMeta = target.map(value => Json.Obj("_target" -> Json.Str(value)))
      _ <- channel.event(
             topic,
             Payload.Event(
               `type` = "form",
               event = binding,
               value = Json.Str(SiteLiveViewHarnessDom.urlEncoded(fields)),
               meta = eventMeta
             ),
             meta
           )
      _ <- outputQueue.take
             .repeatUntil { case (payload, outputMeta) =>
               outputMeta.messageRef.contains(messageRef) && payload.isInstanceOf[Payload.Reply]
             }
             .timeoutFail(new RuntimeException("Timed out waiting for nested LiveView output"))(3.seconds)
    yield ()
end SiteNestedLiveViewHarness

private object SiteLiveViewHarnessDom:
  def selectOne(html: String, selector: String): Element =
    exactlyOne(Jsoup.parseBodyFragment(html).select(selector).asScala.toVector, s"'$selector' element")

  def buttonByLabel(html: String, label: String): Element =
    val buttons = Jsoup
      .parseBodyFragment(html)
      .select("button").asScala.toVector.filter(_.text() == label)
    exactlyOne(buttons, s"button labelled '$label'")

  def formBinding(html: String, selector: String, attribute: String): String =
    val element = selectOne(html, selector)
    if !element.hasAttr(attribute) then
      throw new IllegalArgumentException(s"Element has no $attribute binding.")
    element.attr(attribute)

  def urlEncoded(fields: Vector[(String, String)]): String =
    fields.map { case (name, value) => s"${encode(name)}=${encode(value)}" }.mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def exactlyOne(matches: Vector[Element], description: String): Element =
    matches match
      case Vector(element) => element
      case _ =>
        throw new IllegalArgumentException(s"Expected one $description, found ${matches.size}.")
end SiteLiveViewHarnessDom

private[docs] object SiteNestedLiveViewHarness:
  def join(channel: LiveChannel, instanceId: String): RIO[Scope, SiteNestedLiveViewHarness] =
    val topic = s"lv:$instanceId"
    val meta = Meta(
      joinRef = Some(2),
      messageRef = Some(1),
      topic = topic,
      eventType = WebSocketMessage.Protocol.EventJoin
    )
    for
      entry <- channel.nestedEntry(topic).someOrFail(new NoSuchElementException(topic))
      rejection <- channel.joinNested(topic, entry.token, false, meta, URL.root)
      _ <- ZIO.fail(new IllegalStateException(s"Nested LiveView join rejected: $rejection"))
             .when(rejection.nonEmpty)
      socket <- channel.socket(topic).someOrFail(new NoSuchElementException(topic))
      outputQueue    <- Queue.unbounded[(Payload, Meta)]
      nextMessageRef <- Ref.make(1)
      _              <- ZIO.addFinalizer(outputQueue.shutdown)
      _              <- socket.outbox.runForeach(outputQueue.offer).forkScoped
      _              <- outputQueue.take
    yield new SiteNestedLiveViewHarness(channel, socket, outputQueue, nextMessageRef, topic)
end SiteNestedLiveViewHarness

private[docs] object SiteLiveViewHarness:
  private val Topic              = "lv:docs-test-harness"
  private val ServerSubscription = SubscriptionKey("docs-test-harness-server")
  private val JoinMeta = Meta(
    joinRef = Some(1),
    messageRef = Some(1),
    topic = Topic,
    eventType = WebSocketMessage.Protocol.EventJoin
  )

  def join[Msg: LiveMessageTag, Model](
    liveView: LiveView[Msg, Model],
    runtimeTrace: RuntimeTrace = RuntimeTrace.Disabled
  ): RIO[Scope, SiteLiveViewHarness[Msg, Model]] =
    for
      serverMessages <- Queue.unbounded[Msg]
      outputQueue     <- Queue.unbounded[(Payload, Meta)]
      outputRef       <- Ref.make(Vector.empty[(Payload, Meta)])
      nextMessageRef  <- Ref.make(1)
      _               <- ZIO.addFinalizer(serverMessages.shutdown *> outputQueue.shutdown)
      wrapped = new LiveView[Msg, Model]:
                  override def hooks = liveView.hooks
                  override def pageTitle(model: Model) = liveView.pageTitle(model)

                  def mount(ctx: MountContext) =
                    ctx.subscriptions
                      .start(ServerSubscription)(ZStream.fromQueue(serverMessages)) *>
                      liveView.mount(ctx)

                  def handleMessage(model: Model, ctx: MessageContext) =
                    liveView.handleMessage(model, ctx)

                  def render(model: Model) = liveView.render(model)
      channel <- LiveChannel.make(TokenConfig.default, None, runtimeTrace)
      context = LiveContext(
                  staticChanged = false,
                  nestedLiveViews = channel.nestedRuntime(Topic)
                )
      _ <- channel.join(Topic, "docs-test-token", wrapped, context, JoinMeta, URL.root)(using
             summon[LiveMessageTag[Msg]].classTag
           )
      socket <- channel.socket(Topic).someOrFail(new NoSuchElementException(Topic)).map {
                  _.asInstanceOf[Socket[Msg, Model]]
                }
      _ <- socket.outbox
             .runForeach(output => outputRef.update(_ :+ output) *> outputQueue.offer(output))
             .forkScoped
      _ <- outputQueue.take
    yield new SiteLiveViewHarness(
      channel,
      socket,
      serverMessages,
      outputQueue,
      outputRef,
      nextMessageRef,
      Topic
    )
end SiteLiveViewHarness
