package scalive.render

import java.time.Instant

import zio.ZIO
import zio.test.*

import scalive.*
import scalive.streams.*

object BindingTableSpec extends ZIOSpecDefault:
  private enum FormMsg:
    case Changed(event: RawFormEvent[FormData])
    case Recovered(event: RawFormEvent[FormData])

  private enum RowFormMsg:
    case Changed(row: String, event: RawFormEvent[FormData])

  private final case class TypedData(name: String)
  private val TypedRoot       = FormRoot("typed")
  private val TypedName       = TypedRoot.text("name")
  private val TypedDefinition = TypedRoot.product[TypedData]((name = TypedName))
  private enum TypedIntent(val wireValue: String):
    case Preview extends TypedIntent("preview")
    case Save    extends TypedIntent("save")
  private val TypedSubmitter = TypedDefinition.submitter(TypedIntent.values)(_.wireValue)
  private enum TypedFormMsg:
    case Received(event: TypedDefinition.Event)
    case Submitted(
      event: TypedDefinition.Event,
      intent: Either[FormSubmitter.DecodeError, TypedIntent])

  private final case class NestedItem(name: String)
  private final case class NestedBasket(items: Vector[NestedItem])
  private val NestedRoot       = FormRoot("nested")
  private val NestedItems      = NestedRoot.rows("items")
  private val NestedName       = NestedItems.text("name")
  private val NestedItemRows   = NestedItems.product[NestedItem]((name = NestedName))
  private val NestedDefinition = NestedRoot.product[NestedBasket]((items = NestedItemRows))
  private val NestedAdapter    = PhoenixNestedParamsAdapter(NestedDefinition, NestedItemRows)
  private val NestedSubmitter =
    NestedDefinition.submitter(TypedIntent.values)(_.wireValue)
  private enum NestedFormMsg:
    case Submitted(
      event: NestedAdapter.Event,
      intent: Either[FormSubmitter.DecodeError, TypedIntent])

  private def table(entries: (String, Int)*): BindingTable[Int] =
    val builder = BindingTable.Builder[Int]()
    entries.foreach((id, message) =>
      builder.add(BindingId.fromEncoded(id), BindingOperation(_ => message))
    )
    builder.result().fold(throw _, identity)

  private def message(operation: Option[BindingOperation[Int]]): Option[Int] =
    operation
      .flatMap(_.dispatch(BindingPayload.Params(Map.empty)).toOption)
      .collect { case BindingDispatch.Owner(value) => value }

  private def id(value: String) = BindingId.fromEncoded(value)

  override def spec = suite("BindingTableSpec")(
    test("resolves a dropped binding for a while, and a binding rendered again to its new operation") {
      val start  = Instant.EPOCH
      val first  = table("kept" -> 1, "dropped" -> 2, "back" -> 3)
      val second = table("kept" -> 10).retiring(first, start)
      val third  = table("kept" -> 10, "back" -> 30).retiring(second, start.plusSeconds(1))
      val later  = start.plus(BindingTable.RetiredFor).minusMillis(1)
      val expiry = start.plus(BindingTable.RetiredFor)

      assertTrue(
        second.resolve(id("dropped")).isEmpty,
        message(second.resolveRetired(id("dropped"), later)).contains(2),
        second.resolveRetired(id("dropped"), expiry).isEmpty,
        message(third.resolveRetired(id("dropped"), later)).contains(2),
        message(second.resolveRetired(id("back"), start)).contains(3),
        message(third.resolve(id("back"))).contains(30),
        third.resolveRetired(id("back"), start.plusSeconds(1)).isEmpty,
        second.resolveRetired(id("kept"), start).isEmpty
      )
    },
    test("retains the newest dropped bindings up to its capacity") {
      val capacity = BindingTable.RetiredCapacity
      val start    = Instant.EPOCH
      val old      = table((0 until capacity).map(index => s"old-$index" -> index)*)
      val first    = table("latest" -> -1).retiring(old, start)
      val second   = table().retiring(first, start.plusSeconds(1))
      val oldKept  = (0 until capacity).count(index =>
        second.resolveRetired(id(s"old-$index"), start.plusSeconds(1)).isDefined
      )

      assertTrue(
        message(second.resolveRetired(id("latest"), start.plusSeconds(1))).contains(-1),
        oldKept == capacity - 1
      )
    },
    test("rejects every duplicate binding insertion") {
      val builder   = BindingTable.Builder[Int]()
      val id        = BindingId.fromEncoded("duplicate")
      val operation = BindingOperation[Int](_ => 1)
      val first     = builder.add(id, operation)
      val duplicate = builder.add(id, operation)

      assertTrue(first.isRight, duplicate == Left(RenderError.DuplicateBinding(id)))
    },
    test("tells generated binding IDs apart from browser event names") {
      val compiled = RenderProgram.compile[Unit, Int] { _ =>
        div(
          button(on.click(1)),
          button(on.click(JS.push(2).push(3))),
          form(idAttr := "profile:form", on.change(_ => 4)),
          form(idAttr := "pushing", on.change(JS.push(5)))
        )
      }
      val names = Vector("copied", "save", "b1", "f3:ab", "f2:ab:js:", "j", "js1", "j1:js:", "")

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        generated  = candidate.bindings.ids.map(_.encoded)
      yield assertTrue(
        generated.exists(_.startsWith("b")),
        generated.exists(id => id.startsWith("j") && id.contains(":js:")),
        generated.exists(id => id.startsWith("f") && !id.contains(":js:")),
        generated.exists(id => id.startsWith("f") && id.contains(":js:")),
        generated.forall(BindingId.isGenerated),
        !names.exists(BindingId.isGenerated)
      )
    },
    test("namespaces bindings to the owning render program") {
      val firstProgram  = RenderProgram.compile[Unit, Int](_ => button(on.click(1)))
      val secondProgram = RenderProgram.compile[Unit, Int](_ => button(on.click(2)))

      for
        first         <- ZIO.fromEither(firstProgram)
        second        <- ZIO.fromEither(secondProgram)
        firstRender   <- first.evaluate(())
        secondRender  <- second.evaluate(())
        oldId = firstRender.bindings.ids.head
        replacementId = secondRender.bindings.ids.head
      yield assertTrue(
        oldId != replacementId,
        secondRender.bindings.resolve(BindingId.fromEncoded(oldId.encoded)).isEmpty,
        BindingId.isFrom(oldId, firstRender.programIdentity),
        !BindingId.isFrom(oldId, secondRender.programIdentity),
        !BindingId.isFrom(BindingId.fromEncoded("save"), firstRender.programIdentity)
      )
    },
    test("decodes browser payload inside the typed operation") {
      val compiled = RenderProgram.compile[Unit, Int] { _ =>
        button(on.click((params: Map[String, String]) => params("value").toInt), "submit")
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        result = candidate.bindings
          .resolve(BindingId.fromEncoded(id.encoded)).get
          .dispatch(BindingPayload.Params(Map("value" -> "42")))
      yield assertTrue(result == Right(BindingDispatch.Owner(42)))
    },
    test("signal bindings retain the committed rendered value") {
      val compiled = RenderProgram.compile[Int, Int] { model =>
        button(on.click(model)((value, _) => value), "value")
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(1)
        firstCommitted = first.commit
        second <- program.evaluate(2, Some(firstCommitted))
        id = first.bindings.ids.head
        payload = BindingPayload.Params(Map.empty)
        committedResult = firstCommitted.bindings.resolve(id).get.dispatch(payload)
        candidateResult = second.bindings.resolve(id).get.dispatch(payload)
      yield assertTrue(
        committedResult == Right(BindingDispatch.Owner(1)),
        candidateResult == Right(BindingDispatch.Owner(2))
      )
    },
    test("registers typed JS push messages through checked insertion") {
      val compiled = RenderProgram.compile[Unit, String] { _ =>
        button(on.click(JS.push("save")), "save")
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        id = candidate.bindings.ids.head
        result = candidate.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(
        id.encoded == "j1:js:0",
        result == Right(BindingDispatch.Owner("save")),
        HtmlRenderer.render(candidate.tree).contains("$scalive-unresolved-binding") == false
      )
    },
    test("keeps typed JS push attributes stable across equivalent lifecycle programs") {
      val firstCompiled = RenderProgram.compile[Unit, String] { _ =>
        form(on.change(JS.push("validate")))
      }
      val secondCompiled = RenderProgram.compile[Unit, String] { _ =>
        form(on.change(JS.push("validate")))
      }

      for
        firstProgram  <- ZIO.fromEither(firstCompiled)
        secondProgram <- ZIO.fromEither(secondCompiled)
        first         <- firstProgram.evaluate(())
        second        <- secondProgram.evaluate(())
      yield assertTrue(
        first.bindings.ids == second.bindings.ids,
        HtmlRenderer.render(first.tree) == HtmlRenderer.render(second.tree)
      )
    },
    test("keeps typed form change bindings stable across equivalent lifecycle programs") {
      def compile = RenderProgram.compile[Unit, FormMsg] { _ =>
        form(
          idAttr := "profile-form",
          on.change.form(FormCodec.formData)(FormMsg.Changed(_)),
          on.recover.form(FormCodec.formData)(FormMsg.Recovered(_))
        )
      }

      val recoveredData = FormData.fromMap(Map("note" -> "draft"))
      val payload       = BindingPayload.Form(recoveredData, RawFormEvent.Meta(recovery = true))

      for
        firstProgram  <- ZIO.fromEither(compile)
        secondProgram <- ZIO.fromEither(compile)
        first         <- firstProgram.evaluate(())
        second        <- secondProgram.evaluate(())
        firstChange  = bindingAttribute(first, "phx-change")
        secondChange = bindingAttribute(second, "phx-change")
        firstRecover = bindingAttribute(first, "phx-auto-recover")
        secondRecover = bindingAttribute(second, "phx-auto-recover")
        changeResult = second.bindings.resolve(firstChange).map(_.dispatch(payload))
        recoverResult = second.bindings.resolve(secondRecover).map(_.dispatch(payload))
        changeRecovered = changeResult.exists {
                            case Right(BindingDispatch.Owner(FormMsg.Changed(event))) =>
                              event.raw == recoveredData && event.recovery
                            case _ => false
                          }
        dedicatedRecovered = recoverResult.exists {
                               case Right(BindingDispatch.Owner(FormMsg.Recovered(event))) =>
                                 event.raw == recoveredData && event.recovery
                               case _ => false
                             }
      yield assertTrue(
        firstChange == secondChange,
        firstRecover != secondRecover,
        changeRecovered,
        dedicatedRecovered
      )
    },
    test("derives form change bindings from stable form ids instead of keyed row history") {
      def compile = RenderProgram.compile[Vector[String], RowFormMsg] { rows =>
        div(
          rows.splitBy(identity) { (rowId, _) =>
            form(
              idAttr := s"form-$rowId",
              on.change.form(FormCodec.formData)(RowFormMsg.Changed(rowId, _))
            )
          }
        )
      }

      val recoveredData = FormData.fromMap(Map("note" -> "draft"))
      val payload       = BindingPayload.Form(recoveredData, RawFormEvent.Meta(recovery = true))

      for
        firstProgram  <- ZIO.fromEither(compile)
        secondProgram <- ZIO.fromEither(compile)
        first         <- firstProgram.evaluate(Vector("removed", "kept"))
        second        <- secondProgram.evaluate(Vector("kept"))
        firstChange  = keyedFormBinding(first, "form-kept")
        secondChange = keyedFormBinding(second, "form-kept")
        changeResult = second.bindings.resolve(firstChange).map(_.dispatch(payload))
        changeRecovered = changeResult.exists {
                            case Right(BindingDispatch.Owner(RowFormMsg.Changed("kept", event))) =>
                              event.raw == recoveredData && event.recovery
                            case _ => false
                          }
      yield assertTrue(firstChange == secondChange, changeRecovered)
    },
    test("classifies definition-backed automatic recovery as a recovered event") {
      val compiled = RenderProgram.compile[Unit, TypedFormMsg] { _ =>
        form(idAttr := "typed-form", TypedDefinition.onChange(TypedFormMsg.Received(_)))
      }
      val data = FormData(Vector(TypedName.name -> "Ada"))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        changeId = bindingAttribute(candidate, "phx-change")
        recoverId = bindingAttribute(candidate, "phx-auto-recover")
        changed = candidate.bindings.resolve(changeId).map(
                    _.dispatch(BindingPayload.Form(data, RawFormEvent.Meta(recovery = true)))
                  )
        recovered = candidate.bindings.resolve(recoverId).map(
                      _.dispatch(BindingPayload.Form(data))
                    )
      yield assertTrue(
        changed.exists {
          case Right(BindingDispatch.Owner(TypedFormMsg.Received(event))) =>
            event.kind == FormEventKind.Recovered
          case _ => false
        },
        recovered.exists {
          case Right(BindingDispatch.Owner(TypedFormMsg.Received(event))) =>
            event.kind == FormEventKind.Recovered && event.form.valueOption.contains(TypedData("Ada"))
          case _ => false
        }
      )
    },
    test("decodes a typed submit action from form data without protocol metadata") {
      val compiled = RenderProgram.compile[Unit, TypedFormMsg] { _ =>
        form(
          idAttr := "typed-submit-form",
          TypedDefinition.onSubmit(TypedSubmitter)(TypedFormMsg.Submitted.apply),
          TypedSubmitter.button(TypedIntent.Preview)("Preview"),
          TypedSubmitter.button(TypedIntent.Save)("Save")
        )
      }
      val data = FormData(
        Vector(TypedName.name -> "Ada", TypedSubmitter.name -> TypedIntent.Preview.wireValue)
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        submitId = bindingAttribute(candidate, "phx-submit")
        submitted = candidate.bindings.resolve(submitId).map(
                      _.dispatch(BindingPayload.Form(data))
                    )
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        submitted.exists {
          case Right(BindingDispatch.Owner(TypedFormMsg.Submitted(event, intent))) =>
            event.form.valueOption.contains(TypedData("Ada")) &&
            event.rawSubmitter.isEmpty && intent == Right(TypedIntent.Preview)
          case _ => false
        },
        html.contains("name=\"typed[_scalive_submitter]\""),
        html.contains("value=\"preview\"")
      )
    },
    test("decodes typed submit actions after translating Phoenix nested parameters") {
      val compiled = RenderProgram.compile[Unit, NestedFormMsg] { _ =>
        form(
          idAttr := "nested-typed-form",
          NestedAdapter.onSubmit(NestedSubmitter)(NestedFormMsg.Submitted.apply)
        )
      }
      val data = FormData(
        Vector(
          NestedAdapter.persistentIdName(0) -> "stable",
          NestedAdapter.fieldName(0, NestedName) -> "Ada",
          NestedAdapter.sortName -> "0",
          NestedSubmitter.name -> TypedIntent.Preview.wireValue
        )
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        submitId = bindingAttribute(candidate, "phx-submit")
        submitted = candidate.bindings.resolve(submitId).map(
                      _.dispatch(BindingPayload.Form(data))
                    )
      yield assertTrue(
        submitted.exists {
          case Right(BindingDispatch.Owner(NestedFormMsg.Submitted(event, intent))) =>
            event.form.valueOption.contains(NestedBasket(Vector(NestedItem("Ada")))) &&
            intent == Right(TypedIntent.Preview)
          case _ => false
        }
      )
    },
    test("does not reuse form change bindings across different form ids") {
      def compile(formId: String) = RenderProgram.compile[Unit, Int] { _ =>
        form(idAttr := formId, on.change(_ => 1))
      }

      for
        firstProgram  <- ZIO.fromEither(compile("first-form"))
        secondProgram <- ZIO.fromEither(compile("second-form"))
        first         <- firstProgram.evaluate(())
        second        <- secondProgram.evaluate(())
        firstChange  = bindingAttribute(first, "phx-change")
        secondChange = bindingAttribute(second, "phx-change")
      yield assertTrue(
        firstChange != secondChange,
        second.bindings.resolve(firstChange).isEmpty
      )
    },
    test("rebuilds only the binding groups of the stream rows a turn inserts or deletes") {
      final case class Row(id: Int)
      final case class Feed(stream: LiveStream[Row])
      val identity = LiveStreamIdentity.fresh()
      def entry(row: Row) = LiveStreamEntry(s"rows-${row.id}", row)
      def stream(rows: Vector[Row], inserted: Vector[Row], deleted: Vector[Row]) =
        LiveStream(
          identity,
          "rows",
          1L,
          rows.map(entry),
          inserted.map(row => LiveStreamInsert(entry(row), StreamAt.Last, None, false)),
          deleted.map(entry(_).domId),
          reset = false
        )
      val rows     = Vector.tabulate(5)(Row(_))
      val added    = Row(5)
      val compiled = RenderProgram.compile[Feed, Int] { feed =>
        div(feed.map(_.stream).renderIn(ul)(row => li(on.click(row)((row, _) => row.id))))
      }
      def groups(render: RenderCandidate[Int]) =
        val stream = render.tree.root.children.head.asInstanceOf[EvaluatedNode.Element]
          .children.head.asInstanceOf[EvaluatedNode.Stream]
        render.bindings.rowGroups(stream.id).values.toVector
      def rebuilt(previous: RenderCandidate[Int], next: RenderCandidate[Int]) =
        groups(next).count(group => !groups(previous).exists(_ eq group))

      for
        program  <- ZIO.fromEither(compiled)
        first    <- program.evaluate(Feed(stream(rows, Vector.empty, Vector.empty)))
        inserted <- program.evaluate(
                      Feed(stream(rows :+ added, Vector(added), Vector.empty)),
                      Some(first.commit)
                    )
        deleted <- program.evaluate(
                     Feed(stream(rows.drop(1) :+ added, Vector.empty, Vector(rows.head))),
                     Some(inserted.commit)
                   )
      yield assertTrue(
        groups(inserted).size == 6,
        rebuilt(first, inserted) == 1,
        groups(deleted).size == 5,
        rebuilt(inserted, deleted) == 0,
        inserted.bindings.size == 6,
        deleted.bindings.size == 5
      )
    },
    test("retires the bindings of a row a collection removes and keeps those of the others") {
      final case class Row(id: Int)
      val compiled = RenderProgram.compile[Vector[Row], Int] { rows =>
        div(rows.splitBy(_.id)((id, _) => button(on.click(id))))
      }
      val now = Instant.EPOCH

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector(Row(1), Row(2), Row(3)))
        base = first.commit
        second <- program.evaluate(Vector(Row(1), Row(3)), Some(base))
        committed = second.commit.retiring(base, now)
        removed   = base.bindings.ids.filterNot(committed.bindings.ids.contains)
      yield assertTrue(
        removed.size == 1,
        committed.bindings.size == 2,
        message(committed.bindings.resolveRetired(removed.head, now)).contains(2)
      )
    },
    test("drops the bindings of a row that renders none any more, and adds them back") {
      final case class Row(id: Int, active: Boolean)
      val compiled = RenderProgram.compile[Vector[Row], Int] { rows =>
        div(rows.splitBy(_.id)((id, row) => div(row.map(_.active).when(button(on.click(id))))))
      }
      val active   = Vector(Row(1, true), Row(2, true))
      val inactive = Vector(Row(1, true), Row(2, false))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(active)
        base = first.commit
        second <- program.evaluate(inactive, Some(base))
        hidden = second.commit
        third <- program.evaluate(active, Some(hidden))
      yield assertTrue(
        base.bindings.size == 2,
        hidden.bindings.size == 1,
        third.bindings.ids.toSet == base.bindings.ids.toSet
      )
    },
    test("rejects a row binding that duplicates one in a kept row") {
      final case class Row(id: Int, formId: String)
      val compiled = RenderProgram.compile[Vector[Row], Int] { rows =>
        div(rows.splitBy(identity)((row, _) => form(idAttr := row.formId, on.change(_ => row.id))))
      }

      for
        program   <- ZIO.fromEither(compiled)
        first     <- program.evaluate(Vector(Row(1, "form-1")))
        duplicate <- program
                       .evaluate(Vector(Row(1, "form-1"), Row(2, "form-1")), Some(first.commit))
                       .either
      yield assertTrue(duplicate.left.exists(_.isInstanceOf[RenderError.DuplicateBinding]))
    }
  )

  private def bindingAttribute(candidate: RenderCandidate[?], name: String): BindingId =
    candidate.tree.root.attributes
      .collectFirst {
        case EvaluatedAttribute(`name`, Some(AttributeValue.Text(value)), _, _) =>
          BindingId.fromEncoded(value)
      }.getOrElse(throw IllegalStateException(s"Missing $name binding attribute."))

  private def keyedFormBinding(candidate: RenderCandidate[?], formId: String): BindingId =
    val keyed = candidate.tree.root.children.collectFirst { case value: EvaluatedNode.Keyed => value }
      .getOrElse(throw IllegalStateException("Missing keyed forms."))
    val form = keyed.rows.iterator.map(_.child).find { element =>
      element.attributes.exists {
        case EvaluatedAttribute("id", Some(AttributeValue.Text(`formId`)), _, _) => true
        case _                                                                  => false
      }
    }.getOrElse(throw IllegalStateException(s"Missing keyed form $formId."))

    form.attributes.collectFirst {
      case EvaluatedAttribute("phx-change", Some(AttributeValue.Text(value)), _, _) =>
        BindingId.fromEncoded(value)
    }.getOrElse(throw IllegalStateException(s"Missing phx-change binding on $formId."))
