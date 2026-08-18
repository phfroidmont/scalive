package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object TreeDifferSpec extends ZIOSpecDefault:
  final case class Collision(value: String):
    override def hashCode(): Int = 7

  override def spec = suite("TreeDifferSpec")(
    test("produces initial, exact scalar, and unchanged deltas") {
      val compiled = RenderProgram.compile[String, Nothing] { model =>
        div(dataAttr("state") := model, model)
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate("one")
        same    <- program.evaluate("one", Some(first.commit))
        changed <- program.evaluate("two", Some(same.commit))
        delta = TreeDiffer.diff(same.tree, changed.tree)
      yield assertTrue(
        TreeDiffer.initial(first.tree) == RenderDelta.Replace(first.tree),
        TreeDiffer.diff(first.tree, same.tree) == RenderDelta.Empty,
        delta match
          case RenderDelta.Update(_, changes) =>
            changes.collect { case _: RenderChange.Attribute => 1 }.size == 1 &&
              changes.collect { case _: RenderChange.Text => 1 }.size == 1
          case _ => false
      )
    },
    test("cannot suppress a real update with equal hashes") {
      val compiled = RenderProgram.compile[Collision, Nothing](model => div(model.map(_.value)))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Collision("first"))
        second  <- program.evaluate(Collision("second"), Some(first.commit))
      yield assertTrue(
        Collision("first").hashCode == Collision("second").hashCode,
        TreeDiffer.diff(first.tree, second.tree).isInstanceOf[RenderDelta.Update],
        HtmlRenderer.render(second.tree) == "<div>second</div>"
      )
    },
    test("suppresses output when changed input projects to an equal value") {
      val compiled = RenderProgram.compile[String, Nothing](model => div(model.map(_.length.toString)))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate("one")
        second  <- program.evaluate("two", Some(first.commit))
      yield assertTrue(TreeDiffer.diff(first.tree, second.tree) == RenderDelta.Empty)
    },
    test("does not equate sibling candidates or independent programs") {
      val firstProgram  = RenderProgram.compile[String, Nothing](model => div(model))
      val secondProgram = RenderProgram.compile[String, Nothing](model => div(model))
      val firstStatic   = RenderProgram.compile[Unit, Nothing](_ => div())
      val secondStatic  = RenderProgram.compile[Unit, Nothing](_ => div())

      for
        first       <- ZIO.fromEither(firstProgram)
        second      <- ZIO.fromEither(secondProgram)
        staticLeft  <- ZIO.fromEither(firstStatic)
        staticRight <- ZIO.fromEither(secondStatic)
        committed   <- first.evaluate("base")
        base = committed.commit
        left        <- first.evaluate("left", Some(base))
        right       <- first.evaluate("right", Some(base))
        independent <- second.evaluate("other")
        emptyLeft   <- staticLeft.evaluate(())
        emptyRight  <- staticRight.evaluate(())
      yield assertTrue(
        TreeDiffer.diff(left.tree, right.tree) != RenderDelta.Empty,
        TreeDiffer.diff(committed.tree, independent.tree) != RenderDelta.Empty,
        TreeDiffer.diff(emptyLeft.tree, emptyRight.tree) == RenderDelta.Replace(emptyRight.tree)
      )
    },
    test("updates committed bindings even when rendered HTML is unchanged") {
      val compiled = RenderProgram.compile[Int, Int] { model =>
        button(on.click(model)((value, _) => value), "value")
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        second  <- program.evaluate(2, Some(first.commit))
        id = second.bindings.ids.head
        result = second.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(TreeDiffer.diff(first.tree, second.tree) == RenderDelta.Empty, result == Right(2))
    }
  )
