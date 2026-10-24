package scalive.protocol.phoenix

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*

/** The encoder keeps the projection of what a diff does not reach, so that encoding a diff costs
  * what it changes rather than the size of the page.
  */
object PhoenixUnchangedProjectionSpec extends ZIOSpecDefault:
  import PhoenixRenderedEncoder.{ProjectedNode, ProjectedPart}

  final case class Row(id: Int, label: String)
  final case class Page(counter: Int, rows: Vector[Row])

  private val rows = Vector(Row(1, "one"), Row(2, "two"), Row(3, "three"))

  private def view(page: Signal[Page]): HtmlElement[Nothing] =
    div(
      span(page.map(_.counter.toString)),
      ul(page.map(_.rows).splitBy(_.id)((_, row) => li(row.map(_.label)))),
      page.map(_.counter > 1).when(em("many"))
    )

  private def nodes(node: ProjectedNode): Vector[ProjectedNode] =
    node +: node.parts.flatMap {
      case ProjectedPart.Node(child)  => nodes(child)
      case ProjectedPart.Keyed(keyed) => keyed.rows.flatMap(row => nodes(row.child))
      case _                          => Vector.empty
    }

  private def keyedNode(state: PhoenixRenderedState): ProjectedNode =
    nodes(state.root)
      .find(_.parts.exists {
        case ProjectedPart.Keyed(_) => true
        case _                      => false
      }).get

  private def rowChildren(state: PhoenixRenderedState): Vector[ProjectedNode] =
    keyedNode(state).parts.collect { case ProjectedPart.Keyed(keyed) =>
      keyed.rows.map(_.child)
    }.flatten

  private def encode(pages: Page*) =
    for
      program <- ZIO.fromEither(RenderProgram.compile[Page, Nothing](view))
      first   <- program.evaluate(pages.head)
      initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
      result  <- ZIO.foldLeft(pages.tail)((first.commit, initial._1, Vector.empty[Json.Obj])) {
                  case ((committed, state, payloads), page) =>
                    for
                      candidate <- program.evaluate(page, Some(committed))
                      delta = TreeDiffer.diff(committed.tree, candidate.tree)
                      encoded <- ZIO.fromEither(PhoenixRenderedEncoder.update(state, delta))
                    yield (candidate.commit, encoded._1, payloads :+ encoded._2)
                }
    yield (initial._1, result._2, result._3)

  object Badge extends LiveComponent.Eventless[String, Unit]:
    def mount(props: String, ctx: MountContext) = ZIO.unit
    def view(props: Signal[String], model: Signal[Unit]): HtmlElement[Nothing] = span(props)

  override def spec = suite("PhoenixUnchangedProjectionSpec")(
    test("keeps the component state when a diff changes no component") {
      val rootCompiled = RenderProgram.compile[Int, Nothing](counter =>
        div(
          span(counter.map(_.toString)),
          component(Badge, "first").render("first"),
          component(Badge, "second").render("second")
        )
      )
      val childCompiled = RenderProgram.compile[String, Nothing](label => span(label))
      def resolved(root: RenderCandidate[Nothing], children: Vector[CommittedRender[Nothing]]) =
        ZIO.fromEither(
          root.resolveComponents(
            root.componentRequirements.zip(children).map { (requirement, child) =>
              requirement.resolve(
                ComponentRef.runtime[requirement.Message](child),
                child,
                child.asInstanceOf[CommittedRender[requirement.Message]]
              )
            }
          )
        )

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        first        <- childProgram.evaluate("first").map(_.commit)
        second       <- childProgram.evaluate("second").map(_.commit)
        before       <- rootProgram.evaluate(0).flatMap(resolved(_, Vector(first, second)))
        committed = before.commit
        after   <- rootProgram
                     .evaluate(1, Some(committed)).flatMap(resolved(_, Vector(first, second)))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(before.tree))
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder
                       .update(initial._1, TreeDiffer.diff(committed.tree, after.tree))
                   )
        (state, payload) = updated
      yield assertTrue(
        payload == Json.Obj("0" -> Json.Str("1")),
        state.components eq initial._1.components,
        state.activeCids eq initial._1.activeCids,
        state.tokenCids eq initial._1.tokenCids
      )
    },
    test("keeps the projection of a collection when something else changes") {
      for
        encoded <- encode(Page(0, rows), Page(1, rows))
        (initial, next, payloads) = encoded
      yield assertTrue(
        keyedNode(next) eq keyedNode(initial),
        payloads == Vector(Json.Obj("0" -> Json.Str("1")))
      )
    },
    test("keeps the projection of the rows a diff does not change") {
      for
        encoded <- encode(Page(0, rows), Page(0, rows.updated(1, Row(2, "TWO"))))
        (initial, next, _) = encoded
        before = rowChildren(initial)
        after  = rowChildren(next)
      yield assertTrue(after(0) eq before(0), !(after(1) eq before(1)), after(2) eq before(2))
    },
    test("encodes a conditional and a counter changing together after an unchanged turn") {
      for
        encoded <- encode(Page(0, rows), Page(1, rows), Page(2, rows), Page(2, rows.take(2)))
        (_, _, payloads) = encoded
      yield assertTrue(
        payloads(0) == Json.Obj("0" -> Json.Str("1")),
        payloads(1) == Json.Obj(
          "0" -> Json.Str("2"),
          "2" -> Json.Obj("s" -> Json.Arr(Json.Str("<em>many</em>")))
        ),
        payloads(2) == Json.Obj("1" -> Json.Obj("k" -> Json.Obj("kc" -> Json.Num(2))))
      )
    }
  )
end PhoenixUnchangedProjectionSpec
