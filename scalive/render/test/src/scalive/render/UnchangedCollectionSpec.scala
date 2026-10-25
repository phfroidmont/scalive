package scalive.render

import java.time.Instant

import org.openjdk.jol.info.GraphLayout
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
    * application id to the same instance, rendering `child` unless it rendered something else.
    */
  final private class Instances(child: CommittedRender[Nothing]):
    private val refs     = scala.collection.mutable.Map.empty[String, ComponentRef[Nothing]]
    private val rendered = scala.collection.mutable.Map.empty[String, CommittedRender[Nothing]]

    /** Has the instance of `applicationId` render `render` from now on, as an update of it does. */
    def render(applicationId: String, render: CommittedRender[Nothing]): Unit =
      rendered.update(applicationId, render)

    def resolve[Msg](candidate: RenderCandidate[Msg]): IO[RenderError, RenderCandidate[Msg]] =
      ZIO.fromEither(
        candidate.resolveComponents(candidate.componentRequirements.map { requirement =>
          val ref = refs.getOrElseUpdate(requirement.applicationId, ComponentRef.runtime(Object()))
          requirement.resolve(
            ref,
            ref.asInstanceOf[Object],
            rendered.getOrElse(requirement.applicationId, child)
          )
        })
      )

  private def instances: IO[RenderError, Instances] =
    ZIO.fromEither(badgeChild).flatMap(_.evaluate(())).map(child => Instances(child.commit))

  private def declared(candidate: RenderCandidate[?]): Vector[(String, Any)] =
    candidate.componentRequirements.map(requirement => requirement.applicationId -> requirement.props)

  /** Whether `target` is reachable from `root`: adding it to what `root` reaches adds nothing. */
  private def reaches(root: Object, target: Object): Boolean =
    // Lambdas are hidden classes, whose field offsets JOL finds only this way.
    System.setProperty("jol.magicFieldOffset", "true"): Unit
    val reached = GraphLayout.parseInstance(root).totalCount()
    GraphLayout.parseInstance(root, target).totalCount() == reached

  /** Rows that each hold a component, between two components. */
  private def badgeRows = RenderProgram.compile[Page, Nothing] { page =>
    div(
      component(Badge, "first").render(page.map(_.counter.toString)),
      page.map(_.rows).splitBy(_.id) { (id, row) =>
        li(component(Badge, s"badge-$id").render(row.map(_.label)))
      },
      component(Badge, "last").render("last")
    )
  }

  private def badgeRow(tree: EvaluatedTree, index: Int): EvaluatedNode.Element =
    keyed(tree, 1).rows(index).child

  /** `badgeRows` with `rowCount` rows, as `Instances` resolves them. */
  private def badgeHtml(rowCount: Int): String =
    "<div><span>badge</span>" + "<li><span>badge</span></li>" * rowCount +
      "<span>badge</span></div>"

  final case class Feed(counter: Int, stream: scalive.streams.LiveStream[Row])

  private def streamEntry(row: Row) = scalive.streams.LiveStreamEntry(s"rows-${row.id}", row)

  private def appended(row: Row) =
    scalive.streams.LiveStreamInsert(streamEntry(row), scalive.streams.StreamAt.Last, None, false)

  /** Stream rows that each hold a component, after a component. */
  private def badgeStreamRows = RenderProgram.compile[Feed, Nothing] { feed =>
    div(
      component(Badge, "counter").render(feed.map(_.counter.toString)),
      feed.map(_.stream).renderIn(ul) { row =>
        li(component(Badge, row.map(row => s"badge-${row.id}")).render(row.map(_.label)))
      }
    )
  }

  private def badgeStream(tree: EvaluatedTree): EvaluatedNode.Stream =
    tree.root
      .children(1).asInstanceOf[EvaluatedNode.Element].children.head
      .asInstanceOf[EvaluatedNode.Stream]

  /** Renders `initial` then `changed`, which keeps the row holding `badge-1` while that component
    * renders a large render; renders less in `badge-1`, as an update of it does; then renders
    * `changed` again and `unrelated`, resolving components each time as the runtime does.
    *
    * Returns the last render as HTML, whether the second committed render held the large render,
    * and whether the program still reaches it.
    */
  private def afterComponentUpdate[Input](
    compiled: Either[RenderError, RenderProgram[Input, Nothing]],
    initial: Input,
    changed: Input,
    unrelated: Input
  ): IO[RenderError, (String, Boolean, Boolean)] =
    val largeCompiled =
      RenderProgram.compile[Unit, Nothing](_ => div(span("large"), ul(li("one"), li("two"))))
    def turn(
      program: RenderProgram[Input, Nothing],
      resolution: Instances
    )(
      input: Input,
      previous: Option[CommittedRender[Nothing]]
    ) = program.evaluate(input, previous).flatMap(resolution.resolve).map(_.commit)

    for
      program    <- ZIO.fromEither(compiled)
      resolution <- instances
      render = turn(program, resolution)
      large   <- ZIO.fromEither(largeCompiled).flatMap(_.evaluate(())).map(_.commit)
      small   <- ZIO.fromEither(badgeChild).flatMap(_.evaluate(())).map(_.commit)
      _       <- ZIO.succeed(resolution.render("badge-1", large))
      first   <- render(initial, None)
      kept    <- render(changed, Some(first))
      _       <- ZIO.succeed(resolution.render("badge-1", small))
      updated <- render(changed, Some(kept))
      last    <- render(unrelated, Some(updated))
    yield (
      HtmlRenderer.render(last.tree),
      reaches(kept, large.tree.root),
      reaches(program, large.tree.root)
    )
  end afterComponentUpdate

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
    test("closes a branch in a row that read a signal valid only while the branch is open") {
      final case class Editing(open: Option[Int], rows: Vector[Row])
      val compiled = RenderProgram.compile[Editing, Nothing] { model =>
        div(model.map(_.rows).splitBy(_.id) { (id, row) =>
          li(
            row.map(_.label),
            Signal.option(model.map(_.open.filter(_ == id)))(open =>
              span(open.map(value => s" editing $value"))
            )
          )
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Editing(Some(1), rows))
        base = first.commit
        closed <- program.evaluate(Editing(None, rows), Some(base))
        // The rows change too, so each kept row checks what it read on its own.
        closedAndChanged <- program.evaluate(Editing(None, rows.take(2)), Some(base))
      yield assertTrue(
        HtmlRenderer.render(closed.tree) == "<div><li>one</li><li>two</li><li>three</li></div>",
        HtmlRenderer.render(closedAndChanged.tree) == "<div><li>one</li><li>two</li></div>"
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
    },
    test("keeps the rows holding components that a collection reorders") {
      for
        program    <- ZIO.fromEither(badgeRows)
        resolution <- instances
        base       <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve).map(_.commit)
        reordered = Vector(rows(2), rows(0), rows(1))
        updated <- program.evaluate(Page(0, 0, reordered), Some(base)).flatMap(resolution.resolve)
        fresh   <- program.evaluate(Page(0, 0, reordered))
      yield assertTrue(
        HtmlRenderer.render(updated.tree) == badgeHtml(3),
        declared(updated) == declared(fresh),
        declared(updated).map(_._1) == Vector("first", "badge-3", "badge-1", "badge-2", "last"),
        badgeRow(updated.tree, 0) eq badgeRow(base.tree, 2),
        badgeRow(updated.tree, 1) eq badgeRow(base.tree, 0),
        badgeRow(updated.tree, 2) eq badgeRow(base.tree, 1)
      )
    },
    test("keeps the other rows holding components when a collection removes one") {
      for
        program    <- ZIO.fromEither(badgeRows)
        resolution <- instances
        base       <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve).map(_.commit)
        removed = Vector(rows(0), rows(2))
        updated <- program.evaluate(Page(0, 0, removed), Some(base)).flatMap(resolution.resolve)
        fresh   <- program.evaluate(Page(0, 0, removed))
      yield assertTrue(
        HtmlRenderer.render(updated.tree) == badgeHtml(2),
        declared(updated) == declared(fresh),
        declared(updated).map(_._1) == Vector("first", "badge-1", "badge-3", "last"),
        badgeRow(updated.tree, 0) eq badgeRow(base.tree, 0),
        badgeRow(updated.tree, 1) eq badgeRow(base.tree, 2)
      )
    },
    test("evaluates a reintroduced row holding a component and keeps the others") {
      for
        program    <- ZIO.fromEither(badgeRows)
        resolution <- instances
        base       <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve).map(_.commit)
        removed    <- program
                     .evaluate(Page(0, 0, Vector(rows(0), rows(2))), Some(base))
                     .flatMap(resolution.resolve).map(_.commit)
        reintroduced = rows.updated(1, Row(2, "TWO"))
        updated <- program
                     .evaluate(Page(0, 0, reintroduced), Some(removed))
                     .flatMap(resolution.resolve)
        fresh <- program.evaluate(Page(0, 0, reintroduced))
      yield assertTrue(
        HtmlRenderer.render(updated.tree) == badgeHtml(3),
        declared(updated) == declared(fresh),
        declared(updated)(2) == "badge-2" -> "TWO",
        badgeRow(updated.tree, 0) eq badgeRow(removed.tree, 0),
        !(badgeRow(updated.tree, 1) eq badgeRow(base.tree, 1)),
        badgeRow(updated.tree, 2) eq badgeRow(removed.tree, 1)
      )
    },
    test("keeps the rows holding components that a new stream generation leaves unchanged") {
      val identity = scalive.streams.LiveStreamIdentity.fresh()
      val stream   = scalive.streams.LiveStream(
        identity,
        "rows",
        1L,
        rows.map(streamEntry),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val added = Row(4, "four")
      val next  = scalive.streams.LiveStream(
        identity,
        "rows",
        2L,
        Vector(rows(0), rows(2), added).map(streamEntry),
        Vector(appended(added)),
        Vector("rows-2"),
        reset = false
      )
      def streamRow(tree: EvaluatedTree, index: Int) = badgeStream(tree).rows(index).child

      for
        program    <- ZIO.fromEither(badgeStreamRows)
        resolution <- instances
        base       <- program.evaluate(Feed(0, stream)).flatMap(resolution.resolve).map(_.commit)
        updated    <- program.evaluate(Feed(0, next), Some(base)).flatMap(resolution.resolve)
        fresh      <- program.evaluate(Feed(0, next))
      yield assertTrue(
        HtmlRenderer.render(updated.tree) ==
          "<div><span>badge</span><ul id=\"rows\" phx-update=\"stream\">" +
          Vector(1, 3, 4).map(id => s"<li id=\"rows-$id\"><span>badge</span></li>").mkString +
          "</ul></div>",
        declared(updated) == declared(fresh),
        declared(updated).map(_._1) == Vector("counter", "badge-1", "badge-3", "badge-4"),
        streamRow(updated.tree, 0) eq streamRow(base.tree, 0),
        streamRow(updated.tree, 1) eq streamRow(base.tree, 2),
        badgeStream(updated.tree).operations.inserts.map(_.row.child) ==
          Vector(streamRow(updated.tree, 2))
      )
    },
    test("evaluates the rows holding components from the committed render after a discard") {
      val changed = rows.updated(1, Row(2, "TWO")) :+ Row(4, "four")

      for
        program    <- ZIO.fromEither(badgeRows)
        resolution <- instances
        base       <- program.evaluate(Page(0, 0, rows)).flatMap(resolution.resolve).map(_.commit)
        discarded  <- program.evaluate(Page(0, 0, changed), Some(base)).flatMap(resolution.resolve)
        _          <- discarded.discard
        unrelated  <- program.evaluate(Page(1, 0, rows), Some(base)).flatMap(resolution.resolve)
        _          <- unrelated.discard
        updated    <- program.evaluate(Page(0, 0, changed), Some(base)).flatMap(resolution.resolve)
        fresh      <- program.evaluate(Page(0, 0, changed))
      yield assertTrue(
        keyed(unrelated.tree, 1) eq keyed(base.tree, 1),
        HtmlRenderer.render(updated.tree) == badgeHtml(4),
        declared(updated) == declared(fresh),
        declared(updated).map(_._1) ==
          Vector("first", "badge-1", "badge-2", "badge-3", "badge-4", "last"),
        badgeRow(updated.tree, 0) eq badgeRow(base.tree, 0),
        !(badgeRow(updated.tree, 1) eq badgeRow(base.tree, 1)),
        badgeRow(updated.tree, 2) eq badgeRow(base.tree, 2)
      )
    },
    test("retains no render that a component of a kept row no longer holds") {
      val two = rows.take(2)
      for (html, committed, retained) <- afterComponentUpdate(
                                           badgeRows,
                                           Page(0, 0, two),
                                           Page(0, 0, two.updated(1, Row(2, "TWO"))),
                                           Page(1, 0, two.updated(1, Row(2, "TWO")))
                                         )
      yield assertTrue(html == badgeHtml(2), committed, !retained)
    },
    test("retains no render that a component of a kept nested row no longer holds") {
      final case class Group(id: Int, rows: Vector[Row])
      final case class Groups(counter: Int, groups: Vector[Group])
      val compiled = RenderProgram.compile[Groups, Nothing] { model =>
        div(
          span(model.map(_.counter.toString)),
          model.map(_.groups).splitBy(_.id) { (_, group) =>
            ul(group.map(_.rows).splitBy(_.id) { (id, row) =>
              li(component(Badge, s"badge-$id").render(row.map(_.label)))
            })
          }
        )
      }
      val groups  = Vector(Group(1, rows.take(2)))
      val changed = Vector(Group(1, rows.take(2).updated(1, Row(2, "TWO"))))

      // The group is evaluated again, holding the first row as it was kept.
      for (html, committed, retained) <-
          afterComponentUpdate(compiled, Groups(0, groups), Groups(0, changed), Groups(1, changed))
      yield assertTrue(
        html == "<div><span>1</span><ul>" +
          "<li><span>badge</span></li><li><span>badge</span></li></ul></div>",
        committed,
        !retained
      )
    },
    test("retains no render that a component of a kept stream row no longer holds") {
      val identity = scalive.streams.LiveStreamIdentity.fresh()
      val changed  = Row(2, "TWO")
      def stream(generation: Long, values: Vector[Row], inserted: Vector[Row]) =
        scalive.streams.LiveStream(
          identity,
          "rows",
          generation,
          values.map(streamEntry),
          inserted.map(appended),
          Vector.empty,
          reset = false
        )
      val next = stream(2L, Vector(rows(0), changed), Vector(changed))

      for (html, committed, retained) <- afterComponentUpdate(
                                           badgeStreamRows,
                                           Feed(0, stream(1L, rows.take(2), Vector.empty)),
                                           Feed(0, next),
                                           Feed(1, next)
                                         )
      yield assertTrue(
        html == "<div><span>badge</span><ul id=\"rows\" phx-update=\"stream\">" +
          "<li id=\"rows-1\"><span>badge</span></li><li id=\"rows-2\"><span>badge</span></li>" +
          "</ul></div>",
        committed,
        !retained
      )
    }
  )
end UnchangedCollectionSpec
