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

  def page(cardCount: Int): LiveView[Msg, Int] = new LiveView[Msg, Int]:
    def mount(ctx: MountContext)                       = ZIO.succeed(0)
    def handleMessage(model: Int, ctx: MessageContext) = _ => ZIO.succeed(model + 1)
    override def view(model: Signal[Int])              =
      div(
        button(on.click(Msg.Tick), model.map(_.toString)),
        div((0 until cardCount).map(i => component(Card, s"card-$i").render(i))*)
      )

  def clickBinding(node: EvaluatedNode): Option[String] = node match
    case element: EvaluatedNode.Element =>
      element.attributes
        .collectFirst {
          case attribute if attribute.name == "phx-click" =>
            attribute.value.collect { case AttributeValue.Text(id) => id }
        }.flatten.orElse(element.children.iterator.flatMap(clickBinding).nextOption())
    case _ => None
end ComponentTurnBenchmark

@State(Scope.Benchmark)
class ComponentTurnState:
  import BenchmarkSupport.*
  import ComponentTurnBenchmark.*

  // Each card is a component holding two child components.
  @Param(Array("100", "1000"))
  var cardCount: Int = 0

  var scope: zio.Scope.Closeable           = null
  var connection: RootConnection[Msg, Int] = null
  var outputs: Queue[ConnectionOutput]     = null
  var binding: Option[BindingId]           = None
  var phoenixState: PhoenixRenderedState   = null

  @Setup(Level.Trial)
  def setup(): Unit =
    val config = ConnectionConfig
      .make(8, 8, 8, 8, 8).fold(error => throw RuntimeException(error.toString), identity)
    val metadata = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)
    scope = run(zio.Scope.make)
    outputs = run(Queue.unbounded[ConnectionOutput])
    connection = run(
      RootConnection
        .start(config, metadata, page(cardCount), output => outputs.offer(output).unit)
        .provideEnvironment(ZEnvironment(scope))
    )
    run(outputs.take)
    val tree = run(connection.inspectTree)
    binding = clickBinding(tree.root).map(BindingId.fromEncoded)
    phoenixState = PhoenixRenderedEncoder
      .initial(tree)
      .fold(error => throw RuntimeException(error.toString), _._1)

  @TearDown(Level.Trial)
  def close(): Unit = run(scope.close(Exit.unit))
end ComponentTurnState

/** A click on the root of a page holding many components, none of which the click changes: the turn
  * through the connection and the kernel, and its Phoenix encoding.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
class ComponentTurnBenchmark:
  import BenchmarkSupport.*

  @Benchmark
  def rootClick(state: ComponentTurnState, blackhole: Blackhole): Unit =
    val command = CommandId.fresh().fold(error => throw RuntimeException(error.toString), identity)
    val output  = run(
      state.connection.submitEvent(command, state.binding.get, BindingPayload.Params(Map.empty)) *>
        state.outputs.take
    )
    val delta = output match
      case ConnectionOutput.Reply(_, delta, _) => delta
      case other                               => throw RuntimeException(s"unexpected $other")
    val encoded = PhoenixRenderedEncoder
      .update(state.phoenixState, delta)
      .fold(error => throw RuntimeException(error.toString), identity)
    state.phoenixState = encoded._1
    blackhole.consume(encoded._2)
