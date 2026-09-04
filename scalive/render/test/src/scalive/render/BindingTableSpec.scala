package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object BindingTableSpec extends ZIOSpecDefault:
  private enum FormMsg:
    case Changed(event: RawFormEvent[FormData])
    case Recovered(event: RawFormEvent[FormData])

  private enum RowFormMsg:
    case Changed(row: String, event: RawFormEvent[FormData])

  private final case class TypedData(name: String)
  private val TypedRoot       = FormRoot("typed")
  private val TypedName       = TypedRoot.text("name")
  private val TypedDefinition = TypedRoot.product[TypedData](Tuple1(TypedName))
  private enum TypedFormMsg:
    case Received(event: TypedDefinition.Event)

  override def spec = suite("BindingTableSpec")(
    test("rejects every duplicate binding insertion") {
      val builder   = BindingTable.Builder[Int]()
      val id        = BindingId.fromEncoded("duplicate")
      val operation = BindingOperation[Int](_ => 1)
      val first     = builder.add(id, operation)
      val duplicate = builder.add(id, operation)

      assertTrue(first.isRight, duplicate == Left(RenderError.DuplicateBinding(id)))
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
        secondRender.bindings.resolve(BindingId.fromEncoded(oldId.encoded)).isEmpty
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
