package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object RenderProgramSpec extends ZIOSpecDefault:
  final case class CollidingKey(value: String):
    override def hashCode(): Int = 1

  override def spec = suite("RenderProgramSpec")(
    test("constructs the view once and allocates monotonic identities") {
      var constructions = 0
      val compiled = RenderProgram.compile[Int, Int] { model =>
        constructions += 1
        div(
          dataAttr("count") := model.map(_.toString),
          on.click(model)((value, _) => value),
          span(model.map(_.toString))
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        second  <- program.evaluate(2, Some(first.commit))
        root = first.tree.root
        child = root.children.head.asInstanceOf[EvaluatedNode.Element]
        text = child.children.head.asInstanceOf[EvaluatedNode.Text]
      yield assertTrue(
        constructions == 1,
        root.id.value == 1L,
        child.id.value == 2L,
        text.id.value == 3L,
        root.attributes.head.slot.exists(_.value == 1L),
        first.bindings.ids == second.bindings.ids,
        first.bindings.ids.head.encoded.matches("b[0-9]+:1")
      )
    },
    test("keeps opaque identity types nominal") {
      val templateErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.*
        val id: TemplateId = 1L
      """)
      val slotErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.*
        val id: BindingSlotId = 1L
      """)
      val rowErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.render.*
        val id: RowId = 1L
      """)

      assertTrue(templateErrors.nonEmpty, slotErrors.nonEmpty, rowErrors.nonEmpty)
    },
    test("rejects void children and accepts structural content") {
      val voidResult = RenderProgram.compile[Unit, Nothing](_ => input("child"))
      val choiceResult = RenderProgram.compile[Boolean, Nothing] { selected =>
        div(selected.when(span("selected")))
      }
      val keyedResult = RenderProgram.compile[Unit, Nothing] { _ =>
        div(Vector(1).splitBy(identity)((_, value) => span(value.toString)))
      }

      assertTrue(
        voidResult.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        choiceResult.isRight,
        keyedResult.isRight
      )
    },
    test("evaluates choices, optional content, and structured chooseMod attributes") {
      val compiled = RenderProgram.compile[(Int, Option[String], Boolean), Nothing] { model =>
        val selected = model.map(_._1)
        val optional = model.map(_._2)
        val enabled  = model.map(_._3)
        div(
          enabled.chooseMod(dataAttr("state") := "on", dataAttr("state") := "off"),
          selected.choose(1 -> span("one"), 2 -> strong("two")),
          optional.option(value => em(value))
        )
      }
      val mixed = RenderProgram.compile[Boolean, Nothing] { selected =>
        div(selected.chooseMod(cls := "selected", span("no")))
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate((1, Some("value"), true))
        second  <- program.evaluate((2, None, false), Some(first.commit))
        absent  <- program.evaluate((3, None, true), Some(second.commit))
      yield assertTrue(
        HtmlRenderer.render(first.tree) == "<div data-state=\"on\"><span>one</span><em>value</em></div>",
        HtmlRenderer.render(second.tree) == "<div data-state=\"off\"><strong>two</strong></div>",
        HtmlRenderer.render(absent.tree) == "<div data-state=\"on\"></div>",
        mixed.left.exists(_.isInstanceOf[RenderError.InvalidHtml])
      )
    },
    test("retains keyed rows and bindings across exact-key reorders") {
      final case class Item(key: CollidingKey, value: String)
      val compiled = RenderProgram.compile[Vector[Item], String] { items =>
        div(items.splitBy(_.key) { (_, item) =>
          button(on.click(item)((current, _) => current.value), item.map(_.value))
        })
      }
      val a = CollidingKey("a")
      val b = CollidingKey("b")

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector(Item(a, "A"), Item(b, "B")))
        committed = first.commit
        firstRows = committed.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        ids       = committed.bindings.ids
        second <- program.evaluate(Vector(Item(b, "B2"), Item(a, "A2")), Some(committed))
        secondRows = second.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        values = ids.map(id => second.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty)))
      yield assertTrue(
        firstRows.map(_.id) == secondRows.reverse.map(_.id),
        firstRows.map(_.child.id) == secondRows.reverse.map(_.child.id),
        second.bindings.ids.toSet == ids.toSet,
        values.toSet == Set(
          Right(BindingDispatch.Owner("A2")),
          Right(BindingDispatch.Owner("B2"))
        ),
        HtmlRenderer.render(second.tree) ==
          s"<div><button phx-click=\"${secondRows.head.child.attributes.head.value.get.asInstanceOf[AttributeValue.Text].value}\">B2</button><button phx-click=\"${secondRows(1).child.attributes.head.value.get.asInstanceOf[AttributeValue.Text].value}\">A2</button></div>"
      )
    },
    test("rolls keyed candidates back, retires only after commit, and reintroduces fresh rows") {
      val compiled = RenderProgram.compile[Vector[(String, String)], String] { items =>
        div(items.splitBy(_._1) { (_, item) =>
          button(on.click(item)((current, _) => current._2), item.map(_._2))
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        base = initial.commit
        baseRows = base.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        baseAId = base.bindings.ids.head
        baseAScope = initial.newRowScopes(baseRows.head.id)
        baseBScope = initial.newRowScopes(baseRows(1).id)
        staged <- program.evaluate(Vector("a" -> "A", "c" -> "C"), Some(base))
        stagedRows = staged.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        stagedCScope = staged.newRowScopes(stagedRows(1).id)
        stagedScopeOpen = !stagedCScope.isClosed
        retainedBeforeDiscard = program.retainedKeyedRowCount
        _ <- staged.discard
        stagedScopeClosed = stagedCScope.isClosed
        retried <- program.evaluate(Vector("a" -> "A", "c" -> "C"), Some(base))
        retryRows = retried.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        retryCScope = retried.newRowScopes(retryRows(1).id)
        retainedBeforeCommit = program.retainedKeyedRowCount
        replacement = retried.commit
        retryScopeSurvivedCommit = !retryCScope.isClosed
        retainedAfterCommit = program.retainedKeyedRowCount
        baseBRetiredAfterCommit = baseBScope.isClosed
        _ <- base.close
        retainedASurvivedReplacementClose = !baseAScope.isClosed
        removed <- program.evaluate(Vector("c" -> "C"), Some(replacement))
        removedScopeOpenBeforeCommit = !baseAScope.isClosed
        removedCommit = removed.commit
        removedScopeClosedAfterCommit = baseAScope.isClosed
        reintroduced <- program.evaluate(Vector("a" -> "again", "c" -> "C"), Some(removedCommit))
        reintroducedRows = reintroduced.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        reintroducedAScope = reintroduced.newRowScopes(reintroducedRows.head.id)
        retainedCount = program.retainedKeyedRowCount
        _ <- reintroduced.discard
        reintroducedRollbackClosed = reintroducedAScope.isClosed
        _ <- program.close
        programCloseClosedRetainedC = retryCScope.isClosed
      yield assertTrue(
        stagedRows.head.id == baseRows.head.id,
        retryRows.head.id == baseRows.head.id,
        stagedRows(1).id != retryRows(1).id,
        retainedBeforeDiscard == 2,
        stagedScopeOpen,
        stagedScopeClosed,
        retainedBeforeCommit == 2,
        retainedAfterCommit == 2,
        retryScopeSurvivedCommit,
        baseBRetiredAfterCommit,
        retainedASurvivedReplacementClose,
        removedScopeOpenBeforeCommit,
        removedScopeClosedAfterCommit,
        retainedCount == 1,
        reintroducedRows.head.id != baseRows.head.id,
        !reintroduced.bindings.ids.contains(baseAId),
        reintroducedRollbackClosed,
        programCloseClosedRetainedC
      )
    },
    test("closes a newly created row scope when candidate evaluation fails") {
      var projectedScope = Option.empty[SignalScope]
      val compiled = RenderProgram.compile[Vector[String], String] { items =>
        div(items.splitBy(identity) { (key, item) =>
          projectedScope = SignalEvaluation.scopeOf(item).toOption
          if key == "bad" then button(on.click("first"), on.click("duplicate"))
          else button(on.click(item)((value, _) => value), item)
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(Vector("ok"))
        base = initial.commit
        failed <- program.evaluate(Vector("ok", "bad"), Some(base)).exit
      yield assertTrue(
        failed.isFailure,
        projectedScope.exists(_.isClosed),
        program.retainedKeyedRowCount == 1,
        !initial.newRowScopes.values.head.isClosed
      )
    },
    test("rejects duplicate exact keys without changing retained rows") {
      val staticDuplicate = RenderProgram.compile[Unit, Nothing] { _ =>
        div(Vector("x", "x").splitBy(identity)((_, value) => span(value)))
      }
      val dynamic = RenderProgram.compile[Vector[String], Nothing] { items =>
        div(items.splitBy(identity)((_, item) => span(item)))
      }

      for
        program <- ZIO.fromEither(dynamic)
        initial <- program.evaluate(Vector("x"))
        base = initial.commit
        failed <- program.evaluate(Vector("x", "x"), Some(base)).exit
      yield assertTrue(
        staticDuplicate.left.exists(_.isInstanceOf[RenderError.DuplicateKey]),
        failed.isFailure,
        program.retainedKeyedRowCount == 1,
        HtmlRenderer.render(base.tree) == "<div><span>x</span></div>"
      )
    },
    test("keys signal collections by current index and refreshes retired positions") {
      val compiled = RenderProgram.compile[Vector[String], String] { items =>
        div(items.splitByIndex { (_, item) =>
          button(on.click(item)((value, _) => value), item)
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(Vector("a", "b"))
        base = first.commit
        rows = base.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        changed <- program.evaluate(Vector("A", "B"), Some(base))
        changedCommit = changed.commit
        changedValues = changed.bindings.ids.map(id => changed.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty)))
        short <- program.evaluate(Vector("A"), Some(changedCommit))
        shortCommit = short.commit
        again <- program.evaluate(Vector("A", "new"), Some(shortCommit))
        againRows = again.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
      yield assertTrue(
        changedValues.toSet == Set(
          Right(BindingDispatch.Owner("A")),
          Right(BindingDispatch.Owner("B"))
        ),
        againRows.head.id == rows.head.id,
        againRows(1).id != rows(1).id
      )
    },
    test("combines repeated composite attributes in encounter order") {
      val compiled = RenderProgram.compile[Unit, Nothing] { _ =>
        div(
          dataAttr("first") := "1",
          cls               := " alpha\tbeta ",
          title             := "middle",
          className         := "beta\ngamma",
          rel               := "next",
          rel               := "next prev",
          role              := "button",
          role              := "switch button"
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
      yield assertTrue(
        HtmlRenderer.render(candidate.tree) ==
          "<div data-first=\"1\" class=\"alpha beta gamma\" title=\"middle\" rel=\"next prev\" role=\"button switch\"></div>",
        candidate.tree.root.attributes.map(_.name) ==
          Vector("data-first", "class", "title", "rel", "role")
      )
    },
    test("combines static, signal, optional, and choice composite contributions") {
      final case class Classes(dynamic: String, optional: Option[String], selected: Boolean)
      val compiled = RenderProgram.compile[Classes, Nothing] { model =>
        div(
          cls := "base",
          cls := model.map(_.dynamic),
          cls.optional(model.map(_.optional)),
          model.map(_.selected).chooseMod(cls := "selected", cls := "")
        )
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Classes("active active", Some("wide"), selected = true))
        second <- program.evaluate(
                    Classes("\t", None, selected = false),
                    Some(first.commit)
                  )
      yield assertTrue(
        HtmlRenderer.render(first.tree) ==
          "<div class=\"base active wide selected\"></div>",
        HtmlRenderer.render(second.tree) == "<div class=\"base\"></div>",
        first.tree.root.attributes.size == 1,
        first.tree.root.attributes.head.slot.nonEmpty,
        second.tree.root.attributes.head.slot == first.tree.root.attributes.head.slot
      )
    },
    test("rejects scalar collisions and duplicate non-composite attributes") {
      val mixedClass = RenderProgram.compile[Unit, Nothing] { _ =>
        div(cls := "first", Mod.Attr.Static("CLASS", "second"))
      }
      val staticDuplicate = RenderProgram.compile[Unit, Nothing] { _ =>
        div(idAttr := "first", idAttr := "second")
      }
      val bindingDuplicate = RenderProgram.compile[Unit, Int] { _ =>
        button(on.click(1), on.click(2))
      }

      assertTrue(
        mixedClass.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        staticDuplicate.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        bindingDuplicate.left.exists(_.isInstanceOf[RenderError.InvalidHtml])
      )
    },
    test("rejects mixed composite choices and null composite values") {
      val mixedChoice = RenderProgram.compile[Boolean, Nothing] { selected =>
        div(selected.chooseMod(cls := "selected", Mod.Attr.Static("class", "plain")))
      }
      val staticNull = RenderProgram.compile[Unit, Nothing] { _ =>
        div(cls := null.asInstanceOf[String])
      }
      val dynamicNull = RenderProgram.compile[Unit, Nothing] { model =>
        div(cls := model.map(_ => null: String))
      }
      val optionalNull = RenderProgram.compile[Unit, Nothing] { model =>
        div(cls.optional(model.map(_ => Some(null: String))))
      }

      for
        dynamicProgram  <- ZIO.fromEither(dynamicNull)
        optionalProgram <- ZIO.fromEither(optionalNull)
        dynamicFailure  <- dynamicProgram.evaluate(()).exit
        optionalFailure <- optionalProgram.evaluate(()).exit
      yield assertTrue(
        mixedChoice.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        staticNull.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        dynamicFailure.isFailure,
        optionalFailure.isFailure
      )
    },
    test("samples only the selected composite choice branch") {
      val compiled = RenderProgram.compile[Boolean, Nothing] { selected =>
        div(
          selected.chooseMod(
            cls := selected.map(_ => "selected"),
            cls := selected.map(_ => throw IllegalStateException("unselected branch sampled"))
          )
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(true)
      yield assertTrue(HtmlRenderer.render(candidate.tree) == "<div class=\"selected\"></div>")
    },
    test("validates raw modifier attribute names") {
      val invalid = RenderProgram.compile[Unit, Nothing] { _ =>
        div(Mod.Attr.Static("data-value onclick=alert(1)", "unsafe"))
      }

      assertTrue(invalid.left.exists(_.isInstanceOf[RenderError.InvalidHtml]))
    },
    test("rejects null static and signal scalar values") {
      val staticNull = RenderProgram.compile[Unit, Nothing] { _ =>
        div(null.asInstanceOf[String])
      }
      val dynamicText = RenderProgram.compile[Unit, Nothing] { model =>
        div(model.map(_ => null: String))
      }
      val dynamicAttribute = RenderProgram.compile[Unit, Nothing] { model =>
        div(dataAttr("value") := model.map(_ => null: String))
      }

      for
        textProgram      <- ZIO.fromEither(dynamicText)
        attributeProgram <- ZIO.fromEither(dynamicAttribute)
        textResult       <- textProgram.evaluate(()).exit
        attributeResult  <- attributeProgram.evaluate(()).exit
      yield assertTrue(
        staticNull.left.exists(_.isInstanceOf[RenderError.InvalidHtml]),
        textResult.isFailure,
        attributeResult.isFailure
      )
    },
    test("closes a failed candidate scope without changing committed state") {
      var finalized = false
      val compiled = RenderProgram.compile[Int, Int] { model =>
        div(
          on.click(model)((value, _) => value),
          model.map { value =>
            if value < 0 then throw IllegalArgumentException("negative")
            value.toString
          }
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        initial   <- program.evaluate(1)
        committed = initial.commit
        evaluation = committed.signalEvaluation
        bindingId = committed.bindings.ids.head
        scope     <- CandidateScope.make
        _         <- scope.addFinalizer(ZIO.succeed { finalized = true })
        failed    <- program.evaluateIn(-1, Some(committed), scope).exit
        bindingResult = committed.bindings
          .resolve(bindingId).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(
        failed.isFailure,
        scope.isClosed,
        finalized,
        HtmlRenderer.render(committed.tree) ==
          s"<div phx-click=\"${bindingId.encoded}\">1</div>",
        committed.signalEvaluation == evaluation,
        bindingResult == Right(BindingDispatch.Owner(1)),
        !committed.scope.isClosed
      )
    },
    test("transfers candidate scope ownership exactly once") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        candidate <- program.evaluate(1)
        committed = candidate.commit
        _         <- candidate.discard
        remainedOpen = !committed.scope.isClosed
        secondCommit = scala.util.Try(candidate.commit)
        _            <- committed.close
        closedResult <- program.evaluate(2, Some(committed)).exit
        discarded    <- program.evaluate(3)
        _            <- discarded.discard
        discardedCommit = scala.util.Try(discarded.commit)
      yield assertTrue(
        remainedOpen,
        secondCommit.isFailure,
        committed.scope.isClosed,
        closedResult.isFailure,
        discardedCommit.isFailure
      )
    },
    test("keeps successful candidate resources open until discard") {
      var finalized = false
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        candidate <- program.evaluate(1)
        _         <- candidate.stagedScope.addFinalizer(ZIO.succeed { finalized = true })
        open = !candidate.stagedScope.isClosed && !finalized
        _ <- candidate.discard
      yield assertTrue(open, candidate.stagedScope.isClosed, finalized)
    },
    test("retains only the continuously committed flash projection") {
      val notice = FlashKind("notice")
      var viewConstructions = 0
      var projections       = Vector.empty[String]
      val compiled = RenderProgram.compile[(Int, Map[FlashKind, String]), Nothing](
        model =>
          viewConstructions += 1
          div(model.map(_._1.toString), flash(notice) { message =>
            projections :+= message
            span(message)
          }),
        _._2
      )

      for
        program <- ZIO.fromEither(compiled)
        absent  <- program.evaluate(0 -> Map.empty)
        first   <- program.evaluate(1 -> Map(notice -> "saved"), Some(absent.commit))
        removed <- program.evaluate(2 -> Map.empty, Some(first.commit))
        again   <- program.evaluate(3 -> Map(notice -> "saved"), Some(removed.commit))
      yield assertTrue(
        viewConstructions == 1,
        projections == Vector("saved", "saved"),
        program.retainedFlashProjectionCount == 0,
        HtmlRenderer.render(absent.tree) == "<div>0</div>",
        HtmlRenderer.render(first.tree) == "<div>1<span>saved</span></div>",
        HtmlRenderer.render(removed.tree) == "<div>2</div>",
        HtmlRenderer.render(again.tree) == "<div>3<span>saved</span></div>"
      )
    },
    test("retires flash projection row scopes only after replacement or removal commits") {
      val notice = FlashKind("notice")
      val compiled = RenderProgram.compile[
        (Map[FlashKind, String], Vector[String]),
        Nothing
      ](
        model =>
          val items = model.map(_._2)
          div(flash(notice) { message =>
            val rows = items.splitBy(identity)((_, item) => strong(item))
            if message == "first" then div(rows) else span(rows)
          }),
        _._1
      )

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(Map(notice -> "first") -> Vector("row"))
        initialScope = initial.newRowScopes.values.head
        base = initial.commit
        stagedReplacement <- program.evaluate(
                               Map(notice -> "second") -> Vector("row"),
                               Some(base)
                             )
        stagedScope = stagedReplacement.newRowScopes.values.head
        oldOpenBeforeReplacementCommit = !initialScope.isClosed
        _ <- stagedReplacement.discard
        stagedClosedOnRollback = stagedScope.isClosed
        oldOpenAfterReplacementRollback = !initialScope.isClosed
        replacement <- program.evaluate(
                         Map(notice -> "second") -> Vector("row"),
                         Some(base)
                       )
        replacementScope = replacement.newRowScopes.values.head
        replacementCommit = replacement.commit
        oldClosedAfterReplacementCommit = initialScope.isClosed
        replacementOpenAfterCommit = !replacementScope.isClosed
        stagedRemoval <- program.evaluate(Map.empty -> Vector("row"), Some(replacementCommit))
        replacementOpenBeforeRemovalCommit = !replacementScope.isClosed
        _ <- stagedRemoval.discard
        replacementOpenAfterRemovalRollback = !replacementScope.isClosed
        removal <- program.evaluate(Map.empty -> Vector("row"), Some(replacementCommit))
        removalCommit = removal.commit
        replacementClosedAfterRemovalCommit = replacementScope.isClosed
        reintroduced <- program.evaluate(
                          Map(notice -> "first") -> Vector("row"),
                          Some(removalCommit)
                        )
        reintroducedScope = reintroduced.newRowScopes.values.head
        _ = reintroduced.commit
        reintroducedOpenBeforeProgramClose = !reintroducedScope.isClosed
        _ <- program.close
      yield assertTrue(
        oldOpenBeforeReplacementCommit,
        stagedClosedOnRollback,
        oldOpenAfterReplacementRollback,
        oldClosedAfterReplacementCommit,
        replacementOpenAfterCommit,
        replacementOpenBeforeRemovalCommit,
        replacementOpenAfterRemovalRollback,
        replacementClosedAfterRemovalCommit,
        reintroducedOpenBeforeProgramClose,
        reintroducedScope.isClosed
      )
    },
    test("keeps flash bindings candidate-local across rollback") {
      val notice = FlashKind("notice")
      var dispatched = Vector.empty[String]
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => div(flash(notice) { message =>
          button(on.click { _ =>
            dispatched :+= message
            throw RuntimeException(message)
          }, message)
        }),
        identity
      )

      for
        program   <- ZIO.fromEither(compiled)
        initial   <- program.evaluate(Map(notice -> "old"))
        committed = initial.commit
        oldId     = committed.bindings.ids.head
        staged    <- program.evaluate(Map(notice -> "new"), Some(committed))
        newId     = staged.bindings.ids.head
        _ = staged.bindings.resolve(newId).get.dispatch(BindingPayload.Params(Map.empty))
        _ <- staged.discard
        _ = committed.bindings.resolve(oldId).get.dispatch(BindingPayload.Params(Map.empty))
        restored <- program.evaluate(Map(notice -> "old"), Some(committed))
        restoredCommit = restored.commit
        absent <- program.evaluate(Map.empty, Some(restoredCommit))
        absentCommit = absent.commit
        reintroduced <- program.evaluate(Map(notice -> "old"), Some(absentCommit))
        reintroducedId = reintroduced.bindings.ids.head
      yield assertTrue(
        oldId == newId,
        reintroducedId != oldId,
        dispatched == Vector("new", "old"),
        HtmlRenderer.render(committed.tree) == HtmlRenderer.render(restoredCommit.tree),
        TreeDiffer.diff(committed.tree, restoredCommit.tree) == RenderDelta.Empty,
        program.retainedFlashProjectionCount == 0,
        !committed.scope.isClosed
      )
    },
    test("rejects duplicate projected bindings without replacing committed state") {
      val notice = FlashKind("notice")
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => div(flash(notice)(message => button(on.click(_ => throw RuntimeException(message)), on.click(_ => throw RuntimeException(message))))),
        identity
      )

      for
        program   <- ZIO.fromEither(compiled)
        initial   <- program.evaluate(Map.empty)
        committed = initial.commit
        failed    <- program.evaluate(Map(notice -> "bad"), Some(committed)).exit
      yield assertTrue(
        failed.isFailure,
        program.retainedFlashProjectionCount == 0,
        HtmlRenderer.render(committed.tree) == "<div></div>",
        committed.bindings.isEmpty,
        !committed.scope.isClosed
      )
    },
    test("failed flash candidates preserve committed identity and bounded state") {
      val notice = FlashKind("notice")
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => div(flash(notice) { message =>
          if message == "invalid" then
            button(
              on.click(_ => throw RuntimeException("first")),
              on.click(_ => throw RuntimeException("duplicate"))
            )
          else button(on.click(_ => throw RuntimeException(message)), message)
        }),
        identity
      )

      for
        program   <- ZIO.fromEither(compiled)
        initial   <- program.evaluate(Map(notice -> "one"))
        committed = initial.commit
        bindingId = committed.bindings.ids.head
        failed    <- program.evaluate(Map(notice -> "invalid"), Some(committed)).exit
        changed   <- program.evaluate(Map(notice -> "two"), Some(committed))
        changedCommit = changed.commit
        third <- program.evaluate(Map(notice -> "three"), Some(changedCommit))
        thirdCommit = third.commit
      yield assertTrue(
        failed.isFailure,
        changed.bindings.ids == Vector(bindingId),
        TreeDiffer.diff(committed.tree, changed.tree) match
          case RenderDelta.Update(_, Vector(_: RenderChange.Text)) => true
          case _ => false,
        program.retainedFlashProjectionCount == 1,
        HtmlRenderer.render(thirdCommit.tree).endsWith(">three</button></div>")
      )
    }
  )
