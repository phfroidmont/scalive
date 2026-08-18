package scalive

import zio.*
import zio.test.*

object ViewGraphStructureSpec extends ZIOSpecDefault:

  override def spec = suite("ViewGraphStructureSpec")(
    test("retains both conditional branches and activates one per evaluation") {
      var trueConstructions  = 0
      var falseConstructions = 0
      val graph = ViewGraph.build[Boolean] { enabled =>
        div(
          enabled.choose(
            {
              trueConstructions += 1
              span(cls := "on", "Enabled")
            },
            {
              falseConstructions += 1
              em(cls := "off", "Disabled")
            }
          )
        )
      }

      val initial = graph.evaluate(false, SignalEvaluation.empty, 1L)
      val next    = graph.evaluate(true, initial.evaluation, 2L)

      assertTrue(
        trueConstructions == 1,
        falseConstructions == 1,
        RenderSnapshot.renderHtml(initial.compiled) ==
          "<div><em class=\"off\">Disabled</em></div>",
        RenderSnapshot.renderHtml(next.compiled) ==
          "<div><span class=\"on\">Enabled</span></div>",
        !TreeDiff.diff(initial.compiled, next.compiled).isEmpty
      )
    },
    test("retains an optional projection while updating its scoped value") {
      var constructions = 0
      val graph = ViewGraph.build[Option[String]] { selected =>
        div(
          selected.option { value =>
            constructions += 1
            strong(value)
          }
        )
      }

      val initial = graph.evaluate(Some("one"), SignalEvaluation.empty, 1L)
      val empty   = graph.evaluate(None, initial.evaluation, 2L)
      val next    = graph.evaluate(Some("two"), empty.evaluation, 3L)

      assertTrue(
        constructions == 1,
        RenderSnapshot.renderHtml(initial.compiled) == "<div><strong>one</strong></div>",
        RenderSnapshot.renderHtml(empty.compiled) == "<div></div>",
        RenderSnapshot.renderHtml(next.compiled) == "<div><strong>two</strong></div>"
      )
    },
    test("retains keyed row graphs and their binding IDs across reorder") {
      final case class Row(id: String, label: String)
      var constructions = Map.empty[String, Int].withDefaultValue(0)
      val graph = ViewGraph.build[Vector[Row]] { rows =>
        ul(
          rows.splitBy(_.id) { (id, row) =>
            constructions = constructions.updated(id, constructions(id) + 1)
            li(
              idAttr := id,
              on.click(row.map(value => s"select-${value.id}")),
              row.map(_.label)
            )
          }
        )
      }

      val initialRows = Vector(Row("a", "Alpha"), Row("b", "Beta"))
      val nextRows    = Vector(Row("b", "Beta 2"), Row("a", "Alpha"))
      val initial     = graph.evaluate(initialRows, SignalEvaluation.empty, 1L)
      val next        = graph.evaluate(nextRows, initial.evaluation, 2L)
      val initialIds  = messagesById(initial.compiled)
      val nextIds     = messagesById(next.compiled)

      assertTrue(
        constructions == Map("a" -> 1, "b" -> 1),
        initialIds("select-a") == nextIds("select-a"),
        initialIds("select-b") == nextIds("select-b"),
        RenderSnapshot.renderHtml(next.compiled).contains("Beta 2")
      )
    },
    test("rejects duplicate keyed identities") {
      final case class Row(id: String)
      val graph = ViewGraph.build[Vector[Row]] { rows =>
        ul(rows.splitBy(_.id)((_, row) => li(row.map(_.id))))
      }

      val failure = scala.util.Try(
        graph.evaluate(Vector(Row("same"), Row("same")), SignalEvaluation.empty, 1L)
      ).failed.toOption

      assertTrue(failure.exists(_.isInstanceOf[IllegalArgumentException]))
    },
    test("commits keyed row creation and pruning only after successful evaluation") {
      final case class Model(rows: Vector[String], fail: Boolean)
      var constructions = Map.empty[String, Int].withDefaultValue(0)
      var scopes        = Map.empty[String, Vector[SignalScope]].withDefaultValue(Vector.empty)
      val graph = ViewGraph.build[Model] { model =>
        div(
          model.map(_.rows).splitBy(identity) { (id, row) =>
            constructions = constructions.updated(id, constructions(id) + 1)
            scopes = scopes.updated(id, scopes(id) :+ row.scope)
            span(row)
          },
          model.map(value => if value.fail then throw new RuntimeException("boom") else "")
        )
      }

      val initial = graph.evaluate(Model(Vector("a"), fail = false), SignalEvaluation.empty, 1L)
      val failed = scala.util.Try(
        graph.evaluate(Model(Vector("b"), fail = true), initial.evaluation, 2L)
      )
      val retried = graph.evaluate(Model(Vector("a", "b"), fail = false), initial.evaluation, 3L)

      assertTrue(
        failed.isFailure,
        constructions == Map("a" -> 1, "b" -> 2),
        scopes("b").head.isDisposed,
        !scopes("b").last.isDisposed,
        RenderSnapshot.renderHtml(retried.compiled).contains("<span>a</span><span>b</span>")
      )
    },
    test("disposes pruned row scopes and the complete graph") {
      var rowSignal = Option.empty[Signal[String]]
      val graph = ViewGraph.build[Vector[String]] { rows =>
        div(rows.splitBy(identity) { (_, row) =>
          rowSignal = Some(row)
          span(row)
        })
      }

      val initial     = graph.evaluate(Vector("a"), SignalEvaluation.empty, 1L)
      val removed     = graph.evaluate(Vector.empty, initial.evaluation, 2L)
      val rowDisposed = rowSignal.exists(_.scope.isDisposed)
      val cachePruned = removed.evaluation.samples.keys.forall(!_.scope.isDisposed)
      graph.dispose()
      graph.dispose()
      val graphFailure = scala.util.Try(
        graph.evaluate(Vector.empty, removed.evaluation, 3L)
      ).failed.toOption

      assertTrue(
        rowDisposed,
        cachePruned,
        graphFailure.exists(_.isInstanceOf[IllegalStateException])
      )
    },
    test("supports keyed modifiers nested in staged choices and retained rows") {
      final case class Row(id: String)
      val graph = ViewGraph.build[(Boolean, Vector[Row])] { model =>
        val visible = model.map(_._1)
        val rows    = model.map(_._2)
        div(
          visible.chooseMod(
            rows.splitBy(_.id) { (_, row) =>
              div(
                row.map(_.id),
                List("a", "b").splitBy(identity)((key, value) => span(key, value))
              )
            },
            em("hidden")
          )
        )
      }

      val result = graph.evaluate(true -> Vector(Row("row")), SignalEvaluation.empty, 1L)

      assertTrue(
        RenderSnapshot.renderHtml(result.compiled) ==
          "<div><div>row<span>aa</span><span>bb</span></div></div>"
      )
    },
    test("retains stream snapshot bindings outside the emitted patch rows") {
      final case class Row(id: String, label: String)
      val graph = ViewGraph.build[streams.LiveStream[Row]] { stream =>
        stream.renderIn(ul) { row =>
          val id = row.map(_.id)
          li(
            on.click(row.map(value => s"delete-${value.id}")),
            id,
            row.map(_.label)
          )
        }
      }

      val first = Row("a", "Alpha")
      val second = Row("b", "Beta")
      val initialStream = liveStream(
        snapshot = Vector(first),
        emitted = Vector(first),
        ref = "0"
      )
      val nextStream = liveStream(
        snapshot = Vector(first, second),
        emitted = Vector(second),
        ref = "1",
        inserts = Vector(streams.LiveStreamInsert("row-b", -1, None, None))
      )
      val initial = graph.evaluate(initialStream, SignalEvaluation.empty, 1L)
      val next    = graph.evaluate(nextStream, initial.evaluation, 2L)
      val messages = BindingRegistry.collect[String](next.compiled).values.flatMap(
        _(Map.empty).toOption
      ).toSet

      assertTrue(
        messages == Set("delete-a", "delete-b"),
        RenderSnapshot.renderHtml(next.compiled).contains("Beta"),
        hasSharedComprehension(TreeDiff.initial(initial.compiled))
      )
    },
    test("commits stream row creation and pruning only after successful evaluation") {
      final case class Row(id: String)
      final case class Model(stream: streams.LiveStream[Row], fail: Boolean)
      var constructions = 0
      var scopes        = Vector.empty[SignalScope]
      val graph = ViewGraph.build[Model] { model =>
        div(
          model.map(_.stream).renderIn(ul) { row =>
            constructions += 1
            scopes :+= row.scope
            li(row.map(_.id))
          },
          model.map(value => if value.fail then throw new RuntimeException("boom") else "")
        )
      }

      val rowA = Row("a")
      val rowB = Row("b")
      val initialStream = liveStream(Vector(rowA), Vector(rowA), "0")
      val failedStream  = liveStream(Vector(rowB), Vector(rowB), "1")
      val retriedStream = liveStream(Vector(rowA, rowB), Vector(rowA, rowB), "2")
      val initial = graph.evaluate(Model(initialStream, fail = false), SignalEvaluation.empty, 1L)
      val failed = scala.util.Try(
        graph.evaluate(Model(failedStream, fail = true), initial.evaluation, 2L)
      )
      val retried = graph.evaluate(Model(retriedStream, fail = false), initial.evaluation, 3L)

      assertTrue(
        failed.isFailure,
        constructions == 3,
        scopes(1).isDisposed,
        !scopes(2).isDisposed,
        RenderSnapshot
          .renderHtml(retried.compiled).contains("<li id=\"row-a\">a</li><li id=\"row-b\">b</li>")
      )
    },
    test("rolls retained component rows back with a failed parent evaluation") {
      final case class Model(rows: Vector[String], fail: Boolean)
      var scopes = Map.empty[String, Vector[SignalScope]].withDefaultValue(Vector.empty)

      object RowsComponent
          extends LiveComponent.Eventless[Vector[String], Unit]:
        def mount(props: Vector[String], ctx: MountContext) = ZIO.unit
        override def view(
          props: Signal[Vector[String]],
          model: Signal[Unit],
          self: ComponentRef[Nothing]
        ) =
          div(props.splitBy(identity) { (id, row) =>
            scopes = scopes.updated(id, scopes(id) :+ row.scope)
            span(row)
          })

      val graph = ViewGraph.build[Model] { model =>
        div(
          liveComponent(RowsComponent, "rows", model.map(_.rows)),
          model.map(value => if value.fail then throw new RuntimeException("boom") else "")
        )
      }

      for
        components <- Ref.make(socket.ComponentRuntimeState.empty)
        ctx = LiveContext(
                staticChanged = false,
                components = new socket.SocketComponentUpdateRuntime(components)
              )
        initial <- socket.SocketComponentRuntime.evaluateViewGraph(
                     graph,
                     Model(Vector("a"), fail = false),
                     SignalEvaluation.empty,
                     1L,
                     components,
                     ctx
                   )
        failed <- socket.SocketComponentRuntime
                    .evaluateViewGraph(
                      graph,
                      Model(Vector("b"), fail = true),
                      initial.evaluation,
                      2L,
                      components,
                      ctx
                    ).exit
        retried <- socket.SocketComponentRuntime.evaluateViewGraph(
                     graph,
                     Model(Vector("a", "b"), fail = false),
                     initial.evaluation,
                     3L,
                     components,
                     ctx
                   )
      yield assertTrue(
        failed.isFailure,
        scopes("a").size == 1,
        scopes("b").size == 2,
        scopes("b").head.isDisposed,
        !scopes("b").last.isDisposed,
        RenderSnapshot.renderHtml(retried.compiled).contains("<span>a</span><span>b</span>")
      )
    }
  )

  private def liveStream[A <: Product](
    snapshot: Vector[A],
    emitted: Vector[A],
    ref: String,
    inserts: Vector[streams.LiveStreamInsert] = Vector.empty
  ): streams.LiveStream[A] =
    def entry(value: A): streams.LiveStreamEntry[A] =
      val id = value.productElement(0).toString
      streams.LiveStreamEntry(s"row-$id", value)

    new streams.LiveStream(
      name = "rows",
      entries = emitted.map(entry),
      snapshotEntries = snapshot.map(entry),
      ref = ref,
      inserts = inserts,
      deleteIds = Vector.empty,
      reset = false
    )

  private def messagesById(compiled: RenderSnapshot.Compiled): Map[String, String] =
    BindingRegistry.collect[String](compiled).flatMap { case (id, handler) =>
      handler(Map.empty).toOption.map(_ -> id)
    }

  private def hasSharedComprehension(diff: Diff): Boolean =
    diff match
      case Diff.Comprehension(static, entries, _, _, _) =>
        static.nonEmpty || entries.exists {
          case Diff.Dynamic(_, child)       => hasSharedComprehension(child)
          case Diff.IndexMerge(_, _, child) => hasSharedComprehension(child)
          case _                            => false
        }
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(entry => hasSharedComprehension(entry.diff)) ||
          components.values.exists(hasSharedComprehension)
      case _ => false
end ViewGraphStructureSpec
