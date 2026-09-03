package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*
import scalive.streams.*

object StreamRenderingSpec extends ZIOSpecDefault:
  final case class Item(id: String, label: String)

  private def stream(
    identity: LiveStreamIdentity,
    generation: Long,
    entries: Vector[Item],
    inserted: Vector[(Item, StreamAt, Option[StreamLimit], Boolean)] = Vector.empty,
    deleted: Vector[String] = Vector.empty,
    reset: Boolean = false
  ): LiveStream[Item] =
    LiveStream(
      identity,
      "items",
      generation,
      entries.map(item => LiveStreamEntry(item.id, item)),
      inserted.map { case (item, at, limit, updateOnly) =>
        LiveStreamInsert(LiveStreamEntry(item.id, item), at, limit, updateOnly)
      },
      deleted,
      reset
    )

  private def streamNode(candidate: RenderCandidate[?]): EvaluatedNode.Stream =
    candidate.tree.root.children.head.asInstanceOf[EvaluatedNode.Stream]

  override def spec = suite("StreamRenderingSpec")(
    test("static HTML rejects managed stream handles") {
      val items = stream(
        LiveStreamIdentity.fresh(),
        1L,
        Vector(Item("a", "one"), Item("b", "two"))
      )

      StaticHtml
        .render(div(items.stream((domId, item) => span(idAttr := domId, item.label)))).either
        .map(result => assertTrue(result.left.exists(_.isInstanceOf[RenderError.Unsupported])))
    },
    test("renders the full ordered snapshot and retains signal-backed row identity") {
      val identity = LiveStreamIdentity.fresh()
      val first = stream(identity, 1L, Vector(Item("a", "one"), Item("b", "two")))
      val second = stream(
        identity,
        2L,
        Vector(Item("a", "updated"), Item("b", "two")),
        inserted = Vector((Item("a", "updated"), StreamAt.Index(3), Some(StreamLimit.KeepLast(4)), true))
      )
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing] { model =>
        div(model.stream((domId, item) => span(dataAttr("dom-id") := domId, item.map(_.label))))
      }

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(first)
        base = initial.commit
        updated <- program.evaluate(second, Some(base))
        oldNode = streamNode(initial)
        node = streamNode(updated)
      yield assertTrue(
        HtmlRenderer.render(initial.tree) ==
          "<div><span data-dom-id=\"a\">one</span><span data-dom-id=\"b\">two</span></div>",
        HtmlRenderer.render(updated.tree).contains("updated"),
        oldNode.rows.map(_.child.id) == node.rows.map(_.child.id),
        node.identity eq identity,
        node.generation == 2L,
        node.operations.inserts.head.at == StreamAt.Index(3),
        node.operations.inserts.head.limit.contains(StreamLimit.KeepLast(4)),
        node.operations.inserts.head.updateOnly,
        TreeDiffer.diff(initial.tree, updated.tree) match
          case RenderDelta.Update(_, changes) => changes.exists(_.isInstanceOf[RenderChange.Stream])
          case _                              => false
      )
    },
    test("carries insert delete and reset operations without replacing the stream") {
      val identity = LiveStreamIdentity.fresh()
      val first = stream(identity, 1L, Vector(Item("a", "one"), Item("b", "two")))
      val next = stream(
        identity,
        2L,
        Vector(Item("c", "three"), Item("a", "one")),
        inserted = Vector((Item("c", "three"), StreamAt.First, None, false)),
        deleted = Vector("b"),
        reset = true
      )
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing](model =>
        div(model.stream((_, item) => span(item.map(_.label))))
      )

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(first)
        updated <- program.evaluate(next, Some(initial.commit))
        delta = TreeDiffer.diff(initial.tree, updated.tree)
      yield assertTrue(
        streamNode(updated).operations.deletes == Vector("b"),
        streamNode(updated).operations.reset,
        delta match
          case RenderDelta.Update(_, Vector(change: RenderChange.Stream)) =>
            change.operations.inserts.map(_.row.domId) == Vector("c") &&
              change.operations.deletes == Vector("b") && change.operations.reset
          case _ => false
      )
    },
    test("renders limited insertion operations even when their rows leave the retained snapshot") {
      val identity = LiveStreamIdentity.fresh()
      val limited = stream(
        identity,
        1L,
        Vector(Item("b", "two"), Item("c", "three")),
        inserted = Vector(
          (Item("a", "one"), StreamAt.Index(1), Some(StreamLimit.KeepLast(2)), false),
          (Item("b", "two"), StreamAt.Index(1), Some(StreamLimit.KeepLast(2)), false),
          (Item("c", "three"), StreamAt.Index(1), Some(StreamLimit.KeepLast(2)), false)
        )
      )
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(limited)
        node       = streamNode(candidate)
        openBefore = candidate.newRowScopes.values.forall(!_.isClosed)
        _          = candidate.commit
        closedAfter = candidate.newRowScopes.values.count(_.isClosed)
      yield assertTrue(
        node.rows.map(_.domId) == Vector("b", "c"),
        node.operations.inserts.map(_.row.domId) == Vector("a", "b", "c"),
        HtmlRenderer.render(candidate.tree) ==
          "<div><span id=\"b\">two</span><span id=\"c\">three</span></div>",
        openBefore,
        closedAfter == 1
      )
    },
    test("diffs unrelated row signals but emits no stream operation for one generation") {
      val identity = LiveStreamIdentity.fresh()
      val handle = stream(identity, 1L, Vector(Item("a", "one")))
      val compiled = RenderProgram.compile[(LiveStream[Item], String), Nothing] { model =>
        val handleSignal = model.map(_._1)
        val suffix = model.map(_._2)
        div(handleSignal.stream((_, item) => span(item.map(_.label).zip(suffix).map(_ + _))))
      }

      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(handle -> "!")
        second <- program.evaluate(handle -> "?", Some(first.commit))
        changes = TreeDiffer.diff(first.tree, second.tree) match
          case RenderDelta.Update(_, values) => values
          case _                             => Vector.empty
      yield assertTrue(
        changes.exists(_.isInstanceOf[RenderChange.Text]),
        !changes.exists(_.isInstanceOf[RenderChange.Stream])
      )
    },
    test("rejects duplicate DOM ids") {
      val identity = LiveStreamIdentity.fresh()
      val duplicate = stream(identity, 1L, Vector(Item("a", "one"), Item("a", "two")))
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing](model =>
        div(model.stream((_, item) => span(item.map(_.label))))
      )

      for
        program <- ZIO.fromEither(compiled)
        result <- program.evaluate(duplicate).either
      yield assertTrue(result == Left(RenderError.DuplicateStreamDomId("a")))
    },
    test("retains row bindings by stream identity and DOM id") {
      val identity = LiveStreamIdentity.fresh()
      val first = stream(identity, 1L, Vector(Item("a", "one")))
      val second = stream(
        identity,
        2L,
        Vector(Item("a", "two")),
        inserted = Vector((Item("a", "two"), StreamAt.Last, None, false))
      )
      val compiled = RenderProgram.compile[LiveStream[Item], String](model =>
        div(model.stream((_, item) => button(on.click(item)((value, _) => value.label), "send")))
      )

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(first)
        firstId = initial.bindings.ids.head
        updated <- program.evaluate(second, Some(initial.commit))
        secondId = updated.bindings.ids.head
        dispatched = updated.bindings.resolve(secondId).get
          .dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(
        firstId == secondId,
        dispatched == Right(BindingDispatch.Owner("two"))
      )
    },
    test("replaces a stream when its runtime identity changes") {
      val first = stream(LiveStreamIdentity.fresh(), 1L, Vector(Item("a", "one")))
      val second = stream(LiveStreamIdentity.fresh(), 1L, Vector(Item("a", "one")))
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing](model =>
        div(model.stream((_, item) => span(item.map(_.label))))
      )

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(first)
        updated <- program.evaluate(second, Some(initial.commit))
      yield assertTrue(
        TreeDiffer.diff(initial.tree, updated.tree) match
          case RenderDelta.Update(_, Vector(RenderChange.Replace(_, _: EvaluatedNode.Stream))) => true
          case _ => false
      )
    },
    test("rolls back new rows and retires removed rows only after commit") {
      val identity = LiveStreamIdentity.fresh()
      val first = stream(identity, 1L, Vector(Item("a", "one")))
      val empty = stream(identity, 2L, Vector.empty, deleted = Vector("a"))
      val compiled = RenderProgram.compile[LiveStream[Item], Nothing](model =>
        div(model.stream((_, item) => span(item.map(_.label))))
      )

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(first)
        rowScope = initial.newRowScopes.values.head
        base = initial.commit
        removal <- program.evaluate(empty, Some(base))
        _ <- removal.discard
        openAfterRollback = !rowScope.isClosed
        stillPresent <- program.evaluate(first, Some(base))
        removal2 <- program.evaluate(empty, Some(stillPresent.commit))
        _ = removal2.commit
      yield assertTrue(openAfterRollback, rowScope.isClosed, removal2.newRowScopes.isEmpty)
    }
  )
