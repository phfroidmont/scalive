package scalive.render

import java.time.Instant

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
    }
  )
end UnchangedCollectionSpec
