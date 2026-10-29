package scalive.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import zio.{Exit, Queue, ZEnvironment, ZIO}

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*
import scalive.runtime.connection.*
import scalive.runtime.contracts.CommandId

object ComponentTurnBenchmark:
  enum Msg:
    case Tick
    case Reinsert

  object Leaf extends LiveComponent[Int, Unit, Int]:
    def mount(props: Int, ctx: MountContext)                       = ZIO.succeed(props)
    def handleMessage(props: Int, model: Int, ctx: MessageContext) = _ => ZIO.succeed(model + 1)
    override def view(props: Signal[Int], model: Signal[Int])      = span(model.map(_.toString))

  object Card extends LiveComponent[Int, Unit, Int]:
    def mount(props: Int, ctx: MountContext)                       = ZIO.succeed(props)
    def handleMessage(props: Int, model: Int, ctx: MessageContext) = _ => ZIO.succeed(model + 1)
    override def view(props: Signal[Int], model: Signal[Int])      =
      div(
        model.map(_.toString),
        component(Leaf, props.map(i => s"leaf-$i-a")).render(props),
        component(Leaf, props.map(i => s"leaf-$i-b")).render(props)
      )

  final case class Page(clicks: Int, cards: Vector[Int])

  val Cards = LiveStreamDef.byId[Int, Int]("cards")(identity)

  /** A page of `cardCount` cards, written in the view (`static`), rendered from a list in the model
    * through `splitBy` (`splitBy`), or rendered from a stream (`stream`). Its first button counts
    * clicks; its second inserts the first card of the stream again, which updates its row in place.
    */
  def page(cardCount: Int, layout: String): LiveView[Msg, Page] = new LiveView[Msg, Page]:
    def mount(ctx: MountContext) =
      val created =
        if layout == "stream" then ctx.streams.create(Cards, 0 until cardCount) else ZIO.unit
      created.as(Page(0, Vector.range(0, cardCount)))
    def handleMessage(model: Page, ctx: MessageContext) =
      case Msg.Tick     => ZIO.succeed(model.copy(clicks = model.clicks + 1))
      case Msg.Reinsert => ctx.streams.insert(Cards, 0).as(model)
    override def view(model: Signal[Page]) =
      val cards = layout match
        case "static"  => div((0 until cardCount).map(i => component(Card, s"card-$i").render(i))*)
        case "splitBy" =>
          div(model.map(_.cards).splitBy(identity) { (i, card) =>
            div(component(Card, s"card-$i").render(card))
          })
        case "stream" =>
          div(
            phx.update := PhxUpdate.Stream,
            Cards.stream((domId, card) =>
              div(idAttr := domId, component(Card, card.map(i => s"card-$i")).render(card))
            )
          )
        case other => throw IllegalArgumentException(s"unknown layout $other")
      div(
        button(on.click(Msg.Tick), model.map(_.clicks.toString)),
        button(on.click(Msg.Reinsert)),
        cards
      )

  /** The click bindings of `node`, in document order. */
  def clickBindings(node: EvaluatedNode): Vector[String] = node match
    case element: EvaluatedNode.Element =>
      element.attributes.collect {
        case attribute if attribute.name == "phx-click" =>
          attribute.value.collect { case AttributeValue.Text(id) => id }
      }.flatten ++ element.children.flatMap(clickBindings)
    case _ => Vector.empty

  /** Starts a connection on a page of `cardCount` cards laid out as `layout`. */
  def start(cardCount: Int, layout: String): ComponentTurnConnection =
    import BenchmarkSupport.*
    val config = ConnectionConfig
      .make(8, 8, 8, 8, 8).fold(error => throw RuntimeException(error.toString), identity)
    val metadata   = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)
    val scope      = run(zio.Scope.make)
    val outputs    = run(Queue.unbounded[ConnectionOutput])
    val connection = run(
      RootConnection
        .start(config, metadata, page(cardCount, layout), output => outputs.offer(output).unit)
        .provideEnvironment(ZEnvironment(scope))
    )
    run(outputs.take)
    val tree         = run(connection.inspectTree)
    val bindings     = clickBindings(tree.root).map(BindingId.fromEncoded)
    val phoenixState = PhoenixRenderedEncoder
      .initial(tree)
      .fold(error => throw RuntimeException(error.toString), _._1)
    ComponentTurnConnection(scope, connection, outputs, bindings, phoenixState)

  /** Clicks `binding` and encodes the update for Phoenix. */
  def click(connection: ComponentTurnConnection, binding: BindingId, blackhole: Blackhole): Unit =
    import BenchmarkSupport.*
    val command = CommandId.fresh().fold(error => throw RuntimeException(error.toString), identity)
    val output  = run(
      connection.connection.submitEvent(command, binding, BindingPayload.Params(Map.empty)) *>
        connection.outputs.take
    )
    val delta = output match
      case ConnectionOutput.Reply(_, delta, _) => delta
      case other                               => throw RuntimeException(s"unexpected $other")
    val encoded = PhoenixRenderedEncoder
      .update(connection.phoenixState, delta)
      .fold(error => throw RuntimeException(error.toString), identity)
    connection.phoenixState = encoded._1
    blackhole.consume(encoded._2)
end ComponentTurnBenchmark

final class ComponentTurnConnection(
  val scope: zio.Scope.Closeable,
  val connection: RootConnection[ComponentTurnBenchmark.Msg, ComponentTurnBenchmark.Page],
  val outputs: Queue[ConnectionOutput],
  val bindings: Vector[BindingId],
  var phoenixState: PhoenixRenderedState)

@State(Scope.Benchmark)
class ComponentTurnState:
  import BenchmarkSupport.*

  // Each card is a component holding two child components.
  @Param(Array("100", "1000"))
  var cardCount: Int = 0

  // Whether the cards are written in the view, rendered from the model through `splitBy`, or
  // rendered from a stream.
  @Param(Array("static", "splitBy", "stream"))
  var layout: String = ""

  var page: ComponentTurnConnection = null

  @Setup(Level.Trial)
  def setup(): Unit = page = ComponentTurnBenchmark.start(cardCount, layout)

  @TearDown(Level.Trial)
  def close(): Unit = run(page.scope.close(Exit.unit))

@State(Scope.Benchmark)
class ComponentStreamState:
  import BenchmarkSupport.*

  @Param(Array("100", "1000"))
  var cardCount: Int = 0

  var page: ComponentTurnConnection = null

  @Setup(Level.Trial)
  def setup(): Unit = page = ComponentTurnBenchmark.start(cardCount, "stream")

  @TearDown(Level.Trial)
  def close(): Unit = run(page.scope.close(Exit.unit))

/** Turns on a page holding many components, through the connection and the kernel, and their
  * Phoenix encoding.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class ComponentTurnBenchmark:
  /** A click on the root that changes none of the components. */
  @Benchmark
  def rootClick(state: ComponentTurnState, blackhole: Blackhole): Unit =
    ComponentTurnBenchmark.click(state.page, state.page.bindings(0), blackhole)

  /** Inserting the first card of a stream of cards again, which updates its row in place. */
  @Benchmark
  def streamInsert(state: ComponentStreamState, blackhole: Blackhole): Unit =
    ComponentTurnBenchmark.click(state.page, state.page.bindings(1), blackhole)
