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
    test("diffs the normalized value of one dynamic composite attribute") {
      val compiled = RenderProgram.compile[String, Nothing] { model =>
        div(cls := model)
      }

      for
        program <- ZIO.fromEither(compiled)
        absent  <- program.evaluate(" \t")
        added   <- program.evaluate("active", Some(absent.commit))
        equal   <- program.evaluate(" active active ", Some(added.commit))
        removed <- program.evaluate("", Some(equal.commit))
      yield assertTrue(
        HtmlRenderer.render(absent.tree) == "<div></div>",
        TreeDiffer.diff(absent.tree, added.tree) match
          case RenderDelta.Update(_, Vector(_: RenderChange.Attribute)) => true
          case _                                                        => false,
        TreeDiffer.diff(added.tree, equal.tree) == RenderDelta.Empty,
        TreeDiffer.diff(equal.tree, removed.tree) match
          case RenderDelta.Update(_, Vector(RenderChange.Attribute(_, "class", None))) => true
          case _                                                                        => false
      )
    },
    test("targets the previous containing node when a child identity changes") {
      val compiled = RenderProgram.compile[String, Nothing](model => div(span(model)))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate("first")
        second  <- program.evaluate("second", Some(first.commit))
        currentChild  = second.tree.root.children.head
        replacement   = currentChild match
          case element: EvaluatedNode.Element =>
            element.copy(id = TemplateId(element.id.value + 1000L))
          case _ => currentChild
        currentRoot = second.tree.root.copy(children = Vector(replacement))
        currentTree = second.tree.copy(root = currentRoot)
      yield assertTrue(
        first.tree.root.children.head.id != replacement.id,
        TreeDiffer.diff(first.tree, currentTree) == RenderDelta.Update(
          currentTree.revision,
          Vector(RenderChange.Replace(first.tree.root.id, currentTree.root))
        )
      )
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
      yield assertTrue(
        TreeDiffer.diff(first.tree, second.tree) == RenderDelta.Empty,
        result == Right(BindingDispatch.Owner(2))
      )
    },
    test("diffs flash scalar changes sparsely and structural changes exactly") {
      val notice = FlashKind("notice")
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => div(flash(notice) { message =>
          if message.startsWith("!") then strong(dataAttr("message") := message, message)
          else span(dataAttr("message") := message, message)
        }),
        identity
      )

      for
        program <- ZIO.fromEither(compiled)
        absent  <- program.evaluate(Map.empty)
        first   <- program.evaluate(Map(notice -> "one"), Some(absent.commit))
        second  <- program.evaluate(Map(notice -> "two"), Some(first.commit))
        removed <- program.evaluate(Map.empty, Some(second.commit))
        strong  <- program.evaluate(Map(notice -> "!three"), Some(removed.commit))
        scalarDelta = TreeDiffer.diff(first.tree, second.tree)
      yield assertTrue(
        scalarDelta match
          case RenderDelta.Update(_, changes) =>
            changes.collect { case _: RenderChange.Attribute => 1 }.size == 1 &&
              changes.collect { case _: RenderChange.Text => 1 }.size == 1 &&
              changes.collect { case _: RenderChange.Replace => 1 }.isEmpty
          case _ => false,
        TreeDiffer.diff(second.tree, removed.tree) match
          case RenderDelta.Update(_, Vector(_: RenderChange.Replace)) => true
          case _ => false,
        TreeDiffer.diff(removed.tree, strong.tree) match
          case RenderDelta.Update(_, Vector(_: RenderChange.Replace)) => true
          case _ => false
      )
    },
    test("emits no keyed change for unchanged rows") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing] { items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        same    <- program.evaluate(Vector("a" -> "A", "b" -> "B"), Some(first.commit))
      yield assertTrue(TreeDiffer.diff(first.tree, same.tree) == RenderDelta.Empty)
    },
    test("recursively diffs retained keyed rows in the same order") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing] { items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A"))
        updated <- program.evaluate(Vector("a" -> "updated"), Some(first.commit))
        keyed = updated.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
      yield assertTrue(
        TreeDiffer.diff(first.tree, updated.tree) match
          case RenderDelta.Update(
                _,
                Vector(RenderChange.Keyed(id, Vector(KeyedRowChange.Retain(rowId, changes))))
              ) =>
            id == keyed.id && rowId == keyed.rows.head.id &&
              changes.exists(_.isInstanceOf[RenderChange.Text]) &&
              !changes.exists(_.isInstanceOf[RenderChange.Replace])
          case _ => false
      )
    },
    test("reports the complete new keyed row order on reorder") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing] { items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        reordered <- program.evaluate(Vector("b" -> "B", "a" -> "A"), Some(first.commit))
        keyed = reordered.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
      yield assertTrue(
        TreeDiffer.diff(first.tree, reordered.tree) match
          case RenderDelta.Update(_, Vector(RenderChange.Keyed(id, rows))) =>
            id == keyed.id && rows == keyed.rows.map(row => KeyedRowChange.Retain(row.id, Vector.empty))
          case _ => false
      )
    },
    test("represents keyed insertion and removal by the complete new order") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing] { items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        changed <- program.evaluate(Vector("b" -> "B", "c" -> "C"), Some(first.commit))
        oldKeyed = first.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
        keyed    = changed.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
      yield assertTrue(
        TreeDiffer.diff(first.tree, changed.tree) match
          case RenderDelta.Update(_, Vector(RenderChange.Keyed(id, rows))) =>
            id == keyed.id && rows == Vector(
              KeyedRowChange.Retain(keyed.rows.head.id, Vector.empty),
              KeyedRowChange.Insert(keyed.rows(1))
            ) && !rows.exists {
              case KeyedRowChange.Retain(rowId, _) => rowId == oldKeyed.rows.head.id
              case KeyedRowChange.Insert(_)        => false
            }
          case _ => false
      )
    },
    test("combines a keyed row move with its recursive update") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing] { items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        changed <- program.evaluate(Vector("b" -> "updated", "a" -> "A"), Some(first.commit))
        keyed = changed.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
      yield assertTrue(
        TreeDiffer.diff(first.tree, changed.tree) match
          case RenderDelta.Update(_, Vector(RenderChange.Keyed(id, rows))) =>
            id == keyed.id && rows.map {
              case KeyedRowChange.Retain(rowId, _) => rowId
              case KeyedRowChange.Insert(row)      => row.id
            } == keyed.rows.map(_.id) && rows.headOption.exists {
              case KeyedRowChange.Retain(_, changes) =>
                changes.exists(_.isInstanceOf[RenderChange.Text])
              case KeyedRowChange.Insert(_) => false
            }
          case _ => false
      )
    },
    test("preserves nested semantic changes inside retained keyed rows") {
      val compiled = RenderProgram.compile[Vector[(String, Int)], Nothing] { items =>
        div(items.splitBy(_._1) { (_, item) =>
          span(item.map(_._2).choose(1 -> em("one"), 2 -> strong("two")))
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> 1))
        changed <- program.evaluate(Vector("a" -> 2), Some(first.commit))
        keyed = changed.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed]
      yield assertTrue(
        TreeDiffer.diff(first.tree, changed.tree) match
          case RenderDelta.Update(
                _,
                Vector(RenderChange.Keyed(id, Vector(KeyedRowChange.Retain(_, changes))))
              ) =>
            id == keyed.id && changes.exists {
              case RenderChange.Replace(replacedId, _: EvaluatedNode.Choice) =>
                replacedId != keyed.id
              case _ => false
            }
          case _ => false
      )
    }
  )
