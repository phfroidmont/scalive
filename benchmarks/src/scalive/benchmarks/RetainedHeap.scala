package scalive.benchmarks

import org.openjdk.jol.info.GraphLayout
import zio.Task
import zio.ZIO

import scalive.*
import scalive.protocol.phoenix.{PhoenixRenderedEncoder, PhoenixRenderedState}
import scalive.render.*
import scalive.runtime.connection.StreamStore
import scalive.streams.*

/** Measures the heap a connection keeps between turns for each row of a page holding a keyed list
  * or a stream, after its first turn, or a keyed list whose rows hold a component, after one of
  * them changed and every component rendered again: the model, the committed render with its
  * program, and the Phoenix encoder state.
  *
  * Sizes come from walking the object graph with JOL, so they are exact and repeat on the same JVM.
  * Each page is measured at two row counts and the difference divided by the rows added, which
  * leaves out what every page shares, such as singletons its closures reach.
  *
  * Run it with `mill benchmarks.runMain scalive.benchmarks.RetainedHeap`.
  */
object RetainedHeap:
  import BenchmarkSupport.*

  final case class Row(id: Int, label: String, group: Int)
  final case class ListModel(counter: Int, rows: Vector[Row])
  final case class StreamModel(counter: Int, stream: LiveStream[Row])

  /** A row's details, which its component renders in full or collapsed. */
  object Details extends LiveComponent.Eventless[Row, Unit]:
    def mount(props: Row, ctx: MountContext): Task[Unit]                    = ZIO.unit
    def view(props: Signal[Row], model: Signal[Unit]): HtmlElement[Nothing] =
      details(props, model.map(_ => true))

  private def details(row: Signal[Row], expanded: Signal[Boolean]): HtmlElement[Nothing] =
    div(
      span(row.map(_.label)),
      expanded.when(ul(li(row.map(_.id.toString)), li(row.map(_.group.toString)), li("more")))
    )

  val Rows: LiveStreamDef[Row, Int] = LiveStreamDef.byId[Row, Int]("rows")(_.id)

  private val SmallRowCount = 1000
  private val LargeRowCount = 10000

  /** What a connection keeps for a page: its model data, its render, and its encoder state. */
  final private class Retained(
    val data: Vector[Object],
    val render: Vector[Object],
    val encoder: PhoenixRenderedState,
    val close: () => Unit)

  /** Bytes retained per row, by page and part, such as `keyedList.render`. */
  def measure(): Vector[(String, Long)] =
    // Lambdas are hidden classes, whose field offsets JOL finds only this way.
    System.setProperty("jol.magicFieldOffset", "true"): Unit
    Vector(
      "keyedList"     -> keyedList,
      "stream"        -> stream,
      "componentList" -> componentList
    ).flatMap { case (page, retain) =>
      val small = sizes(retain(SmallRowCount))
      val large = sizes(retain(LargeRowCount))
      small.zip(large).map { case ((part, smallBytes), (_, largeBytes)) =>
        s"$page.$part" -> Math.round(
          (largeBytes - smallBytes).toDouble / (LargeRowCount - SmallRowCount)
        )
      }
    }

  def main(args: Array[String]): Unit =
    measure().foreach { case (name, bytes) => println(f"$name%-24s $bytes%6d B/row") }

  // Each part counts only what the parts before it do not reach.
  private def sizes(retained: Retained): Vector[(String, Long)] =
    try
      val data    = size(retained.data)
      val render  = size(retained.data ++ retained.render)
      val encoder = size(retained.data ++ retained.render :+ retained.encoder)
      Vector(
        "data"    -> data,
        "render"  -> (render - data),
        "encoder" -> (encoder - render),
        "total"   -> encoder
      )
    finally retained.close()

  private def size(roots: Vector[Object]): Long = GraphLayout.parseInstance(roots*).totalSize()

  private def row(id: Int): Row = Row(id, s"row-$id", id % 17)

  private def keyedList(rowCount: Int): Retained =
    def view(model: Signal[ListModel]): HtmlElement[String] =
      div(
        span(model.map(_.counter.toString)),
        table(
          tbody(
            model.map(_.rows).splitBy(_.id) { (id, row) =>
              tr(
                dataAttr("row-id") := id.toString,
                td(row.map(_.label)),
                td(row.map(_.group.toString)),
                td(button(on.click(s"select-$id"), "select"))
              )
            }
          )
        )
      )
    val initial = ListModel(0, Vector.tabulate(rowCount)(row))
    afterSecondTurn(view, initial, initial.copy(counter = 1), Vector.empty)

  private def stream(rowCount: Int): Retained =
    def view(model: Signal[StreamModel]): HtmlElement[String] =
      div(
        span(model.map(_.counter.toString)),
        model
          .map(_.stream).renderIn(ul)(row =>
            li(row.map(_.label), button(on.click("select"), "select"))
          )
      )
    val created = StreamStore.empty.create(Rows, Vector.tabulate(rowCount)(row))
    val initial = StreamModel(0, created.stream)
    afterSecondTurn(view, initial, initial.copy(counter = 1), Vector(created.store.settle))

  /** Each row holds a component, which renders its details expanded. One row changes, which keeps
    * the others, then every component renders them collapsed, as an update of it does, and the
    * counter changes. What a component renders is reached from the committed render; the components
    * share one program, which a connection would not, so their programs are left out.
    */
  private def componentList(rowCount: Int): Retained =
    def view(model: Signal[ListModel]): HtmlElement[String] =
      div(
        span(model.map(_.counter.toString)),
        table(
          tbody(
            model.map(_.rows).splitBy(_.id) { (id, row) =>
              tr(td(row.map(_.label)), td(component(Details, s"details-$id").render(row)))
            }
          )
        )
      )
    val program  = RenderProgram.compile[ListModel, String](view).fold(throw _, identity)
    val children = RenderProgram
      .compile[(Row, Boolean), Nothing](input => details(input.map(_._1), input.map(_._2)))
      .fold(throw _, identity)
    val refs     = scala.collection.mutable.Map.empty[String, ComponentRef[Nothing]]
    val rendered = scala.collection.mutable.Map.empty[String, CommittedRender[Nothing]]

    // Renders each row's component, then resolves them all into `candidate` as the runtime does.
    def resolve(
      candidate: RenderCandidate[String],
      rows: Vector[Row],
      expanded: Boolean
    ): CommittedRender[String] =
      rows.foreach { row =>
        val id       = s"details-${row.id}"
        val previous = rendered.get(id)
        rendered.update(id, run(children.evaluate(row -> expanded, previous)).commit)
        previous.foreach(render => run(render.close))
      }
      val resolutions = candidate.componentRequirements.map { requirement =>
        val ref = refs.getOrElseUpdate(requirement.applicationId, ComponentRef.runtime(Object()))
        requirement.resolve(ref, ref.asInstanceOf[Object], rendered(requirement.applicationId))
      }
      candidate.resolveComponents(resolutions).fold(throw _, identity).commit

    def turn(
      model: ListModel,
      previous: CommittedRender[String],
      state: PhoenixRenderedState,
      expanded: Boolean
    ): (CommittedRender[String], PhoenixRenderedState) =
      val next  = resolve(run(program.evaluate(model, Some(previous))), model.rows, expanded)
      val delta = TreeDiffer.diff(previous.tree, next.tree)
      run(previous.close)
      next -> PhoenixRenderedEncoder.update(state, delta).fold(encodingFailed, _._1)

    val initial           = ListModel(0, Vector.tabulate(rowCount)(row))
    val changed           = initial.copy(rows = initial.rows.updated(0, Row(0, "changed", 0)))
    val first             = resolve(run(program.evaluate(initial)), initial.rows, expanded = true)
    val joined            = PhoenixRenderedEncoder.initial(first.tree).fold(encodingFailed, _._1)
    val (kept, keptState) = turn(changed, first, joined, expanded = true)
    val (collapsed, collapsedState) = turn(changed, kept, keptState, expanded = false)
    val unrelated                   = changed.copy(counter = 1)
    val (last, lastState)           = turn(unrelated, collapsed, collapsedState, expanded = false)
    Retained(
      Vector(unrelated),
      Vector(last, program),
      lastState,
      () =>
        run(
          last.close *> ZIO.foreachDiscard(rendered.values)(_.close) *> program.close *>
            children.close
        )
    )
  end componentList

  // Renders `initial`, then `next`, committing each and encoding both as a connection does.
  private def afterSecondTurn[Model <: Object](
    view: Signal[Model] => HtmlElement[String],
    initial: Model,
    next: Model,
    state: Vector[Object]
  ): Retained =
    val program = RenderProgram.compile[Model, String](view).fold(throw _, identity)
    val first   = run(program.evaluate(initial)).commit
    val joined  = PhoenixRenderedEncoder.initial(first.tree).fold(encodingFailed, _._1)
    val second  = run(program.evaluate(next, Some(first))).commit
    val delta   = TreeDiffer.diff(first.tree, second.tree)
    val updated = PhoenixRenderedEncoder.update(joined, delta).fold(encodingFailed, _._1)
    run(first.close)
    Retained(
      next +: state,
      Vector(second, program),
      updated,
      () => run(second.close *> program.close)
    )

  private def encodingFailed(error: Object): Nothing = throw RuntimeException(error.toString)
end RetainedHeap
