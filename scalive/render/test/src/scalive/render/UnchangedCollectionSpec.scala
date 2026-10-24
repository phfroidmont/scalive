package scalive.render

import java.time.Instant

import zio.IO
import zio.Task
import zio.ZIO
import zio.test.*

import scalive.*

object UnchangedCollectionSpec extends ZIOSpecDefault:
  final case class Row(id: Int, label: String)
  final case class Page(counter: Int, selected: Int, rows: Vector[Row])

  object Badge extends LiveComponent.Eventless[String, Unit]:
    def mount(props: String, ctx: MountContext): Task[Unit] = ZIO.unit
    def view(props: Signal[String], model: Signal[Unit]): HtmlElement[Nothing] = span(props)

  private val rows = Vector(Row(1, "one"), Row(2, "two"), Row(3, "three"))

  private def keyed(tree: EvaluatedTree, index: Int): EvaluatedNode.Keyed =
    tree.root.children(index).asInstanceOf[EvaluatedNode.Keyed]

  private def changeCount(delta: RenderDelta): Int = delta match
    case RenderDelta.Update(_, changes) => changes.size
    case _                              => -1

  private val badgeChild = RenderProgram.compile[Unit, Nothing](_ => span("badge"))

  /** Resolves the components of a render as the runtime does for components it keeps: each
    * application id to the same instance, rendering `child`.
    */
  final private class Instances(child: CommittedRender[Nothing]):
    private val refs = scala.collection.mutable.Map.empty[String, ComponentRef[Nothing]]

    def resolve[Msg](candidate: RenderCandidate[Msg]): IO[RenderError, RenderCandidate[Msg]] =
      ZIO.fromEither(
        candidate.resolveComponents(candidate.componentRequirements.map { requirement =>
          val ref = refs.getOrElseUpdate(requirement.applicationId, ComponentRef.runtime(Object()))
          requirement.resolve(ref, ref.asInstanceOf[Object], child)
        })
      )

  private def instances: IO[RenderError, Instances] =
    ZIO.fromEither(badgeChild).flatMap(_.evaluate(())).map(child => Instances(child.commit))

  private def declared(candidate: RenderCandidate[?]): Vector[(String, Any)] =
    candidate.componentRequirements.map(requirement => requirement.applicationId -> requirement.props)

  override def spec = suite("UnchangedCollectionSpec")(
    test("reuses a collection whose items and outside signals did not change") {
      val compiled = RenderProgram.compile[Page, String] { page =>
        div(
          span(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id) { (id, row) =>
            button(on.click(s"select-$id"), row.map(_.label))
          }
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Page(0, 0, rows))
        base = first.commit
        second <- program.evaluate(Page(1, 0, rows), Some(base))
      yield assertTrue(
        keyed(second.tree, 1) eq keyed(base.tree, 1),
        changeCount(TreeDiffer.diff(base.tree, second.tree)) == 1,
        second.bindings.ids.toSet == base.bindings.ids.toSet
      )
    },
    test("updates the rows of a collection when a signal they read from outside changes") {
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        val selected = page.map(_.selected)
        div(
          span(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id) { (id, row) =>
            li(cls := selected.map(value => if value == id then "on" else "off"), row.map(_.label))
          }
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Page(0, 1, rows))
        base = first.commit
        second <- program.evaluate(Page(0, 2, rows), Some(base))
      yield assertTrue(
        HtmlRenderer.render(second.tree) ==
          "<div><span>0</span><li class=\"off\">one</li><li class=\"on\">two</li><li class=\"off\">three</li></div>"
      )
    },
    test("updates nested rows that read a signal from outside both collections") {
      final case class Group(id: Int, rows: Vector[Row])
      final case class Groups(highlight: String, groups: Vector[Group])
      val compiled = RenderProgram.compile[Groups, Nothing] { model =>
        val highlight = model.map(_.highlight)
        div(model.map(_.groups).splitBy(_.id) { (_, group) =>
          ul(group.map(_.rows).splitBy(_.id) { (_, row) =>
            li(row.combineWithFn(highlight)((row, mark) => s"${row.label}$mark"))
          })
        })
      }
      val groups = Vector(Group(1, rows.take(2)), Group(2, rows.drop(2)))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Groups("", groups))
        base = first.commit
        second <- program.evaluate(Groups("!", groups), Some(base))
      yield assertTrue(
        HtmlRenderer.render(second.tree) ==
          "<div><ul><li>one!</li><li>two!</li></ul><ul><li>three!</li></ul></div>"
      )
    },
    test("keeps the signal values of a reused collection for its next update") {
      var labels   = 0
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        div(
          span(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id) { (_, row) =>
            li(row.map { value =>
              labels += 1
              value.label
            })
          }
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Page(0, 0, rows))
        base = first.commit
        unrelated <- program.evaluate(Page(1, 0, rows), Some(base))
        afterUnrelated = unrelated.commit
        before         = labels
        changed <- program.evaluate(
                     Page(1, 0, rows.updated(1, Row(2, "TWO"))),
                     Some(afterUnrelated)
                   )
      yield assertTrue(
        labels - before == 1,
        HtmlRenderer.render(changed.tree) ==
          "<div><span>1</span><li>one</li><li>TWO</li><li>three</li></div>"
      )
    },
    test("keeps the signal values of the rows a changed collection kept") {
      var ids      = 0
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        val selected = page.map(_.selected)
        div(page.map(_.rows).splitBy(_.id) { (_, row) =>
          li(
            row.map { value =>
              ids += 1
              value.id.toString
            },
            row.combineWithFn(selected)((row, selected) => if row.id == selected then "*" else "")
          )
        })
      }
      val changed = rows.updated(1, Row(2, "TWO"))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Page(0, 0, rows))
        second  <- program.evaluate(Page(0, 0, changed), Some(first.commit))
        before = ids
        // Every row reads the selection, so each is evaluated again, from its kept samples.
        third <- program.evaluate(Page(0, 3, changed), Some(second.commit))
      yield assertTrue(
        ids == before,
        HtmlRenderer.render(third.tree) == "<div><li>1</li><li>2</li><li>3*</li></div>"
      )
    },
    test("reuses a stream whose generation did not change") {
      final case class Feed(counter: Int, stream: scalive.streams.LiveStream[Row])
      val identity = scalive.streams.LiveStreamIdentity.fresh()
      val stream   = scalive.streams.LiveStream(
        identity,
        "rows",
        1L,
        rows.map(row => scalive.streams.LiveStreamEntry(s"rows-${row.id}", row)),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[Feed, Nothing] { feed =>
        div(span(feed.map(_.counter.toString)), feed.map(_.stream).renderIn(ul)(row => li(row.map(_.label))))
      }
      def streamNode(tree: EvaluatedTree) =
        tree.root.children(1).asInstanceOf[EvaluatedNode.Element].children.head

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Feed(0, stream))
        base = first.commit
        second <- program.evaluate(Feed(1, stream), Some(base))
      yield assertTrue(streamNode(second.tree) eq streamNode(base.tree))
    },
    test("updates a stream row when a signal it reads from outside changes") {
      final case class Feed(selected: Int, stream: scalive.streams.LiveStream[Row])
      val stream = scalive.streams.LiveStream(
        scalive.streams.LiveStreamIdentity.fresh(),
        "rows",
        1L,
        rows.map(row => scalive.streams.LiveStreamEntry(s"rows-${row.id}", row)),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[Feed, Nothing] { feed =>
        val selected = feed.map(_.selected)
        div(feed.map(_.stream).renderIn(ul)(row =>
          li(row.combineWithFn(selected)((row, selected) => if row.id == selected then "*" else ""))
        ))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Feed(1, stream))
        second  <- program.evaluate(Feed(2, stream), Some(first.commit))
      yield assertTrue(
        HtmlRenderer.render(second.tree) ==
          "<div><ul id=\"rows\" phx-update=\"stream\"><li id=\"rows-1\"></li><li id=\"rows-2\">*</li><li id=\"rows-3\"></li></ul></div>"
      )
    },
    test("drops the bindings of a collection that is no longer rendered") {
      val compiled = RenderProgram.compile[(Boolean, Vector[Row]), String] { model =>
        div(
          model.map(_._1).when(
            ul(model.map(_._2).splitBy(_.id)((id, _) => li(on.click(s"row-$id"))))
          ),
          button(on.click("always"))
        )
      }
      val now = Instant.EPOCH

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate((true, rows))
        base = first.commit
        hidden <- program.evaluate((false, rows), Some(base))
        hiddenCommit = hidden.commit.retiring(base, now)
        rowIds       = base.bindings.ids.filterNot(hidden.bindings.ids.contains)
      yield assertTrue(
        rowIds.size == 3,
        hidden.bindings.ids.size == 1,
        rowIds.forall(id => hiddenCommit.bindings.resolve(id).isEmpty),
        rowIds.forall(id => hiddenCommit.bindings.resolveRetired(id, now).isDefined)
      )
    },
    test("rejects a binding that duplicates one in a reused collection") {
      val compiled = RenderProgram.compile[(Boolean, Vector[Row]), String] { model =>
        div(
          model.map(_._2).splitBy(_.id) { (id, _) =>
            form(idAttr := s"form-$id", on.change(_ => "row"))
          },
          model.map(_._1).when(form(idAttr := "form-1", on.change(_ => "outside")))
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate((false, rows))
        base = first.commit
        duplicate <- program.evaluate((true, rows), Some(base)).either
      yield assertTrue(duplicate.left.exists(_.isInstanceOf[RenderError.DuplicateBinding]))
    },
    test("reuses a whole render when its input did not change") {
      var samples  = 0
      val compiled = RenderProgram.compile[Page, String] { page =>
        div(
          button(on.click("go"), page.map { value =>
            samples += 1
            value.counter.toString
          })
        )
      }
      val now = Instant.EPOCH

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Page(0, 0, rows))
        base = first.commit
        before = samples
        same <- program.evaluate(Page(0, 0, rows), Some(base))
        committed = same.commit.retiring(base, now)
      yield assertTrue(
        same.tree eq base.tree,
        samples == before,
        committed.bindings.ids == base.bindings.ids
      )
    },
    test("tells which inputs render a committed render again without evaluating it") {
      final case class Feed(counter: Int, stream: scalive.streams.LiveStream[Row])
      val stream = scalive.streams.LiveStream(
        scalive.streams.LiveStreamIdentity.fresh(),
        "rows",
        1L,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val plain = RenderProgram.compile[Page, Nothing] { page =>
        div(span(page.map(_.counter.toString)))
      }
      val withComponent = RenderProgram.compile[Page, Nothing] { page =>
        div(component(Badge, "badge").render(page.map(_.counter.toString)))
      }
      val withStream = RenderProgram.compile[Feed, Nothing] { feed =>
        div(feed.map(_.stream).renderIn(ul)(row => li(row.map(_.label))))
      }
      val childCompiled = RenderProgram.compile[Unit, Nothing](_ => span("badge"))

      for
        plainProgram     <- ZIO.fromEither(plain)
        plainRender      <- plainProgram.evaluate(Page(0, 0, rows)).map(_.commit)
        componentProgram <- ZIO.fromEither(withComponent)
        childProgram     <- ZIO.fromEither(childCompiled)
        unresolved       <- componentProgram.evaluate(Page(0, 0, rows))
        child            <- childProgram.evaluate(())
        token = Object()
        resolved <- ZIO.fromEither(
                      unresolved.resolveComponents(
                        Vector(
                          unresolved.componentRequirements.head
                            .resolve(ComponentRef.runtime(token), token, child)
                        )
                      )
                    )
        componentRender = resolved.commit
        streamProgram <- ZIO.fromEither(withStream)
        streamRender  <- streamProgram.evaluate(Feed(0, stream)).map(_.commit)
        again         <- plainProgram.evaluate(Page(0, 0, rows), Some(plainRender))
        closed        <- plainProgram.evaluate(Page(0, 0, rows)).map(_.commit)
        _             <- closed.close
      yield assertTrue(
        plainProgram.rendersAgain(Page(0, 0, rows), plainRender),
        again.tree eq plainRender.tree,
        !plainProgram.rendersAgain(Page(1, 0, rows), plainRender),
        !plainProgram.rendersAgain(Page(0, 0, rows), closed),
        componentProgram.rendersAgain(Page(0, 0, rows), componentRender),
        !componentProgram.rendersAgain(Page(1, 0, rows), componentRender),
        !streamProgram.rendersAgain(Feed(0, stream), streamRender)
      )
    },
    test("evaluates a render with an unchanged input when it holds a component") {
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        div(component(Badge, "badge").render(page.map(_.counter.toString)))
      }
      val childCompiled = RenderProgram.compile[Unit, Nothing](_ => span("badge"))

      for
        program      <- ZIO.fromEither(compiled)
        childProgram <- ZIO.fromEither(childCompiled)
        first        <- program.evaluate(Page(0, 0, rows))
        child        <- childProgram.evaluate(())
        token = Object()
        resolved <- ZIO.fromEither(
                      first.resolveComponents(
                        Vector(
                          first.componentRequirements.head
                            .resolve(ComponentRef.runtime(token), token, child)
                        )
                      )
                    )
        base = resolved.commit
        same <- program.evaluate(Page(0, 0, rows), Some(base))
      yield assertTrue(same.componentRequirements.size == 1)
    },
    test("keeps a subtree without components when resolving components") {
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        div(
          component(Badge, "badge").render(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id)((_, row) => li(row.map(_.label)))
        )
      }
      val childCompiled = RenderProgram.compile[Unit, Nothing](_ => span("badge"))

      for
        program      <- ZIO.fromEither(compiled)
        childProgram <- ZIO.fromEither(childCompiled)
        candidate    <- program.evaluate(Page(0, 0, rows))
        child        <- childProgram.evaluate(())
        requirement = candidate.componentRequirements.head
        token       = Object()
        resolved <- ZIO.fromEither(
                      candidate.resolveComponents(
                        Vector(requirement.resolve(ComponentRef.runtime(token), token, child))
                      )
                    )
      yield assertTrue(keyed(resolved.tree, 1) eq keyed(candidate.tree, 1))
    },
    test("keeps a render whose components resolve again to the instances and renders they hold") {
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        div(
          span(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id)((id, _) => li(component(Badge, s"badge-$id").render("")))
        )
      }

      for
        program    <- ZIO.fromEither(compiled)
        resolution <- instances
        resolved   <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve)
        again      <- resolution.resolve(resolved)
      yield assertTrue(again.tree.root eq resolved.tree.root)
    },
    test("reuses the rows of a collection that hold components when nothing they read changed") {
      var labels   = 0
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        div(
          span(page.map(_.counter.toString)),
          component(Badge, "first").render(page.map(_.counter.toString)),
          page.map(_.rows).splitBy(_.id) { (id, row) =>
            li(component(Badge, s"badge-$id").render(row.map { value =>
              labels += 1
              value.label
            }))
          },
          component(Badge, "last").render("last")
        )
      }
      val changed = rows.updated(1, Row(2, "TWO"))
      def row(tree: EvaluatedTree, index: Int) = keyed(tree, 2).rows(index).child

      for
        program    <- ZIO.fromEither(compiled)
        resolution <- instances
        base       <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve).map(_.commit)
        before = labels
        unrelated <- program.evaluate(Page(1, 0, rows), Some(base)).flatMap(resolution.resolve)
        afterUnrelated = labels
        committed      = unrelated.commit
        updated <- program.evaluate(Page(1, 0, changed), Some(committed))
        afterUpdated = labels
        fresh <- program.evaluate(Page(1, 0, changed))
      yield assertTrue(
        afterUnrelated == before,
        keyed(unrelated.tree, 2) eq keyed(base.tree, 2),
        declared(unrelated) == Vector(
          "first"   -> "1",
          "badge-1" -> "one",
          "badge-2" -> "two",
          "badge-3" -> "three",
          "last"    -> "last"
        ),
        afterUpdated - afterUnrelated == 1,
        row(updated.tree, 0) eq row(committed.tree, 0),
        !(row(updated.tree, 1) eq row(committed.tree, 1)),
        row(updated.tree, 2) eq row(committed.tree, 2),
        declared(updated) == declared(fresh),
        declared(updated)(2) == "badge-2" -> "TWO"
      )
    },
    test("evaluates the rows holding components again when a signal they read from outside changes") {
      val compiled = RenderProgram.compile[Page, Nothing] { page =>
        val selected = page.map(_.selected)
        div(page.map(_.rows).splitBy(_.id) { (id, row) =>
          li(component(Badge, s"badge-$id").render(row.combineWithFn(selected) { (row, selected) =>
            if row.id == selected then s"${row.label}*" else row.label
          }))
        })
      }

      for
        program    <- ZIO.fromEither(compiled)
        resolution <- instances
        base       <- program.evaluate(Page(0, 1, rows)).flatMap(resolution.resolve).map(_.commit)
        selected   <- program.evaluate(Page(0, 2, rows), Some(base))
      yield assertTrue(
        declared(selected) == Vector("badge-1" -> "one", "badge-2" -> "two*", "badge-3" -> "three")
      )
    },
    test("declares the components of kept nested rows where evaluating them would") {
      final case class Group(id: Int, rows: Vector[Row])
      final case class Groups(counter: Int, groups: Vector[Group])
      val compiled = RenderProgram.compile[Groups, Nothing] { model =>
        div(
          component(Badge, "counter").render(model.map(_.counter.toString)),
          model.map(_.groups).splitBy(_.id) { (groupId, group) =>
            ul(
              component(Badge, s"group-$groupId").render("group"),
              group.map(_.rows).splitBy(_.id) { (id, row) =>
                li(component(Badge, s"badge-$id").render(row.map(_.label)))
              }
            )
          }
        )
      }
      val groups  = Vector(Group(1, rows.take(2)), Group(2, rows.drop(2)))
      val changed = groups.updated(0, Group(1, Vector(Row(1, "ONE"), Row(2, "two"))))
      def group(tree: EvaluatedTree, index: Int) = keyed(tree, 1).rows(index).child

      for
        program    <- ZIO.fromEither(compiled)
        resolution <- instances
        base <- program.evaluate(Groups(0, groups)).flatMap(resolution.resolve).map(_.commit)
        unrelated <- program.evaluate(Groups(1, groups), Some(base)).flatMap(resolution.resolve)
        committed = unrelated.commit
        updated <- program.evaluate(Groups(1, changed), Some(committed))
        fresh   <- program.evaluate(Groups(1, changed))
      yield assertTrue(
        keyed(unrelated.tree, 1) eq keyed(base.tree, 1),
        declared(unrelated).map(_._1) ==
          Vector("counter", "group-1", "badge-1", "badge-2", "group-2", "badge-3"),
        group(updated.tree, 1) eq group(committed.tree, 1),
        declared(updated) == declared(fresh),
        declared(updated)(2) == "badge-1" -> "ONE"
      )
    },
    test("reuses a stream whose rows hold components") {
      final case class Feed(counter: Int, stream: scalive.streams.LiveStream[Row])
      val stream = scalive.streams.LiveStream(
        scalive.streams.LiveStreamIdentity.fresh(),
        "rows",
        1L,
        rows.map(row => scalive.streams.LiveStreamEntry(s"rows-${row.id}", row)),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[Feed, Nothing] { feed =>
        div(
          component(Badge, "counter").render(feed.map(_.counter.toString)),
          feed.map(_.stream).renderIn(ul) { row =>
            li(component(Badge, row.map(row => s"badge-${row.id}")).render(row.map(_.label)))
          }
        )
      }
      def streamNode(tree: EvaluatedTree) =
        tree.root.children(1).asInstanceOf[EvaluatedNode.Element].children.head

      for
        program    <- ZIO.fromEither(compiled)
        resolution <- instances
        base      <- program.evaluate(Feed(0, stream)).flatMap(resolution.resolve).map(_.commit)
        unrelated <- program.evaluate(Feed(1, stream), Some(base)).flatMap(resolution.resolve)
      yield assertTrue(
        streamNode(unrelated.tree) eq streamNode(base.tree),
        declared(unrelated) == Vector(
          "counter" -> "1",
          "badge-1" -> "one",
          "badge-2" -> "two",
          "badge-3" -> "three"
        )
      )
    }
  )
end UnchangedCollectionSpec
