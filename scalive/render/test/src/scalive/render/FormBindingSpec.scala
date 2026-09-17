package scalive.render

import scala.util.Try

import zio.ZIO
import zio.test.*

import scalive.*

object FormBindingSpec extends ZIOSpecDefault:
  final private case class Profile(name: String)
  private val ProfileRoot       = FormRoot("profile")
  private val Name              = ProfileRoot.text("name").required(FieldIssue("Name is required"))
  private val ProfileDefinition = ProfileRoot.product[Profile](Tuple1(Name))

  private enum ProfileMsg:
    case Updated(update: ProfileDefinition.Update)
    case Submitted(event: ProfileDefinition.Event)
    case BlurValue(value: String)

  final private case class Consent(accepted: Boolean)
  private val ConsentRoot       = FormRoot("consent")
  private val Accepted          = ConsentRoot.field("accepted", FieldInput.checkbox())
  private val ConsentDefinition = ConsentRoot.product[Consent](Tuple1(Accepted))

  private enum ConsentMsg:
    case Updated(update: ConsentDefinition.Update)
    case Submitted(event: ConsentDefinition.Event)

  final private case class Item(name: String)
  final private case class Basket(items: Vector[Item])
  private val BasketRoot       = FormRoot("basket")
  private val Items            = BasketRoot.rows("items")
  private val ItemName         = Items.text("name").required(FieldIssue("Item name is required"))
  private val ItemRows         = Items.product[Item](Tuple1(ItemName))
  private val BasketDefinition = BasketRoot.product[Basket](Tuple1(ItemRows))
  private val RowA             = FormRowKey.from[Items.type]("row_a").toOption.get
  private val RowB             = FormRowKey.from[Items.type]("row_b").toOption.get

  private enum BasketMsg:
    case Updated(update: BasketDefinition.Update)
    case Submitted(event: BasketDefinition.Event)

  override def spec = suite("FormBindingSpec")(
    test("message-only blur handlers compose without consuming the browser value") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(binding.field(Name).onBlur(ProfileMsg.BlurValue("fixed")).text())
      }
      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial())
        id      = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        message = candidate.bindings.resolve(id).get.dispatch(BindingPayload.Params(Map.empty))
      yield assertTrue(message == Right(BindingDispatch.Owner(ProfileMsg.BlurValue("fixed"))))
    },
    test("rejects overrides of form identity and control interaction bindings") {
      val formOverride = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(idAttr := "different")
      }
      val controlOverride = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(binding.field(Name).text(on.blur(ProfileMsg.BlurValue("override"))))
      }
      val feedbackOverride = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(binding.field(Name).errorFeedback(_ => "error", idAttr := "other"))
      }
      assertTrue(formOverride.isLeft, controlOverride.isLeft, feedbackOverride.isLeft)
    },
    test("compiles the public binding shape with WhenUsed defaults") {
      val compiled = profileProgram()

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("Ada")))
        html      = HtmlRenderer.render(candidate.tree)
        controlId = s"profile-${Name.address.id}"
      yield assertTrue(
        candidate.bindings.size == 3,
        html.contains("<form id=\"profile\""),
        html.contains("phx-change="),
        html.contains("phx-auto-recover="),
        html.contains("phx-submit="),
        html.contains(s"id=\"$controlId\""),
        html.contains(s"aria-describedby=\"${controlId}_errors\""),
        !html.contains("profile-scalive-blur-trigger"),
        !html.contains("phx-blur=")
      )
    },
    test(
      "Boolean checkbox initials and typed updates preserve binding identity and checked state"
    ) {
      val unchecked = ConsentDefinition.initial(Accepted.initial(false))
      val checked   = ConsentDefinition.initial(Accepted.initial(true))

      for
        program      <- ZIO.fromEither(consentProgram())
        initialFalse <- program.evaluate(unchecked)
        initialTrue  <- program.evaluate(checked)
        updatedTrue  <-
          program.evaluate(unchecked.updated(Accepted, true), Some(initialFalse.commit))
        updatedFalse <- program.evaluate(checked.updated(Accepted, false), Some(initialTrue.commit))
        falseHtml  = HtmlRenderer.render(initialFalse.tree)
        trueHtml   = HtmlRenderer.render(initialTrue.tree)
        candidates = Vector(initialFalse, initialTrue, updatedTrue, updatedFalse)
        controlId  = s"consent-${Accepted.address.id}"
      yield assertTrue(
        unchecked.valueOption.contains(Consent(false)),
        checked.valueOption.contains(Consent(true)),
        unchecked.field(Accepted).rawValues.isEmpty,
        checked.field(Accepted).rawValues == Vector("true"),
        !falseHtml.contains(" checked"),
        trueHtml.contains(" checked"),
        HtmlRenderer.render(updatedTrue.tree).contains(" checked"),
        !HtmlRenderer.render(updatedFalse.tree).contains(" checked"),
        candidates.forall { candidate =>
          val html = HtmlRenderer.render(candidate.tree)
          candidate.bindings.size == 3 &&
          html.contains(s"id=\"$controlId\"") &&
          html.contains(s"name=\"${Accepted.name}\"") &&
          html.contains("type=\"checkbox\"") &&
          html.contains("value=\"true\"") &&
          html.contains(s"aria-describedby=\"${controlId}_errors\"") &&
          html.contains(s"id=\"${controlId}_errors\" phx-feedback-for=\"${Accepted.name}\"") &&
          !html.contains("aria-invalid=") &&
          !html.contains("type=\"hidden\"")
        }
      )
      end for
    },
    test("checkbox custom tokens agree with the codec, including an empty checked token") {
      ZIO
        .foreach(Vector("yes", "")) { token =>
          val root       = FormRoot("consent")
          val accepted   = root.field("accepted", FieldInput.checkbox(checkedValue = token))
          val definition = root.product[Consent](Tuple1(accepted))
          val checked    = definition.initial(accepted.initial(true))
          val unchecked  = checked.updated(accepted, false)
          val compiled   = RenderProgram.compile[definition.Form, Unit] { source =>
            val binding = source.bind(DomRef("consent"), _ => (), _ => ())
            binding.render(binding.field(accepted).checkbox(token))
          }

          for
            program <- ZIO.fromEither(compiled)
            before  <- program.evaluate(checked)
            after   <- program.evaluate(unchecked, Some(before.commit))
            beforeHtml = HtmlRenderer.render(before.tree)
            afterHtml  = HtmlRenderer.render(after.tree)
          yield assertTrue(
            checked.valueOption.contains(Consent(true)),
            unchecked.valueOption.contains(Consent(false)),
            checked.field(accepted).rawValues == Vector(token),
            unchecked.field(accepted).rawValues.isEmpty,
            beforeHtml.contains(s"value=\"$token\""),
            afterHtml.contains(s"value=\"$token\""),
            beforeHtml.contains(" checked"),
            !afterHtml.contains(" checked")
          )
        }.map(_.reduce(_ && _))
    },
    test("an absent checkbox change is unchanged from false and clears true") {
      val unchecked = ConsentDefinition.initial(Accepted.initial(false))
      val checked   = ConsentDefinition.initial(Accepted.initial(true))

      for
        program   <- ZIO.fromEither(consentProgram())
        candidate <- program.evaluate(unchecked)
        update = candidate.bindings
                   .resolve(rootBinding(candidate, "phx-change")).get
                   .dispatch(BindingPayload.Form(FormData(Vector.empty)))
                   .toOption.collect { case BindingDispatch.Owner(ConsentMsg.Updated(value)) =>
                     value
                   }.get
        fromFalse = update.applyTo(unchecked)
        fromTrue  = update.applyTo(checked)
      yield assertTrue(
        fromFalse.kind == FormUpdateKind.Changed,
        !fromFalse.valuesChanged,
        fromFalse.form.valueOption.contains(Consent(false)),
        fromTrue.valuesChanged,
        fromTrue.form.valueOption.contains(Consent(false)),
        fromTrue.form.field(Accepted).rawValues.isEmpty
      )
    },
    test("checkbox submissions show invalid raw feedback while retaining checked tokens") {
      val cases = Vector(
        (Vector("false"), "invalid_checkbox", false),
        (Vector("false", "true"), "duplicate_value", true)
      )

      ZIO
        .foreach(cases) { case (raw, code, checked) =>
          for
            program   <- ZIO.fromEither(consentProgram())
            candidate <- program.evaluate(ConsentDefinition.initial())
            submitted = candidate.bindings
                          .resolve(rootBinding(candidate, "phx-submit")).get
                          .dispatch(BindingPayload.Form(FormData(raw.map(Accepted.name -> _))))
                          .toOption.collect {
                            case BindingDispatch.Owner(ConsentMsg.Submitted(event)) => event
                          }.get
            rendered <- program.evaluate(submitted.form, Some(candidate.commit))
            field = submitted.form.field(Accepted)
            html  = HtmlRenderer.render(rendered.tree)
          yield assertTrue(
            submitted.kind == FormEventKind.Submitted,
            submitted.valueOption.isEmpty,
            field.rawValues == raw,
            field.visibleErrors.map(_.issue.code) == Vector(Some(code)),
            submitted.form.interaction.visibility == ErrorVisibility.All,
            html.contains("aria-invalid=\"true\""),
            html.contains(s"<span class=\"form-error\">$code</span>"),
            html.contains("value=\"true\""),
            html.contains(" checked") == checked
          )
        }.map(_.reduce(_ && _))
    },
    test("AfterBlur renders its trigger and ordered marker command") {
      val compiled = profileProgram(FormFeedback.AfterBlur)

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("Ada")))
        html          = HtmlRenderer.render(candidate.tree)
        setIndex      = html.indexOf("set_attr")
        dispatchIndex = html.indexOf("dispatch")
        removeIndex   = html.indexOf("remove_attr")
      yield assertTrue(
        html.contains("id=\"profile-scalive-blur-trigger\""),
        html.contains("phx-blur="),
        html.contains("set_attr"),
        html.contains("phx-value-scalive-blur"),
        html.contains("profile[name]"),
        html.contains("#profile-scalive-blur-trigger"),
        setIndex >= 0,
        setIndex < dispatchIndex,
        dispatchIndex < removeIndex
      )
    },
    test("preserves structural validation and rejects an update from another schema") {
      val compiled        = profileProgram()
      val OtherRoot       = FormRoot("profile")
      val OtherName       = OtherRoot.text("name")
      val OtherDefinition = OtherRoot.product[Profile](Tuple1(OtherName))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("current")))
        changeId   = rootBinding(candidate, "phx-change")
        dispatched = candidate.bindings
                       .resolve(changeId).get.dispatch(
                         BindingPayload.Form(FormData(Vector("profile[name" -> "raw")))
                       )
        update = dispatched.toOption.collect {
                   case BindingDispatch.Owner(ProfileMsg.Updated(value)) => value
                 }.get
        applied     = update.applyTo(ProfileDefinition.initial(Name.initial("latest")))
        wrongSchema = Try {
                        update
                          .asInstanceOf[OtherDefinition.Update]
                          .applyTo(OtherDefinition.initial(OtherName.initial("other")))
                      }
      yield assertTrue(
        applied.form.errors.all.exists(_.issue.code.contains("unterminated_bracket")),
        wrongSchema.isFailure
      )
    },
    test("scopes control and feedback ids to independent instances") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val first  = source.bind(DomRef("first"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val second = source.bind(DomRef("second"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val firstName  = first.field(Name)
        val secondName = second.field(Name)
        div(
          first.render(
            firstName.text(firstName.validationAttributes),
            firstName.errorFeedback(_ => "error")
          ),
          second.render(
            secondName.text(secondName.validationAttributes),
            secondName.errorFeedback(_ => "error")
          )
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("Ada")))
        html     = HtmlRenderer.render(candidate.tree)
        firstId  = s"first-${Name.address.id}"
        secondId = s"second-${Name.address.id}"
      yield assertTrue(
        candidate.bindings.size == 6,
        html.contains(s"id=\"$firstId\""),
        html.contains(s"aria-describedby=\"${firstId}_errors\""),
        html.contains(s"id=\"${firstId}_errors\" phx-feedback-for=\"profile[name]\""),
        html.contains(s"id=\"$secondId\""),
        html.contains(s"aria-describedby=\"${secondId}_errors\""),
        html.contains(s"id=\"${secondId}_errors\" phx-feedback-for=\"profile[name]\"")
      )
    },
    test("semantic control blur applies to the latest form state") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(
          DomRef("profile"),
          ProfileMsg.Updated(_),
          ProfileMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        val control = binding.field(Name)
        binding.render(control.text(), button(idAttr := "blur-name", on.click(control.blurred)))
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("rendered")))
        blurId = bindingOnId(candidate, "blur-name", "phx-click")
        update = candidate.bindings
                   .resolve(blurId).get
                   .dispatch(BindingPayload.Params(Map.empty)).toOption.collect {
                     case BindingDispatch.Owner(ProfileMsg.Updated(value)) => value
                   }.get
        latest  = ProfileDefinition.initial(Name.initial("newer")).updated(Name, "")
        applied = update.applyTo(latest)
      yield assertTrue(
        applied.kind == FormUpdateKind.Blurred,
        !applied.valuesChanged,
        applied.target.contains(Name.address),
        applied.form.field(Name).fieldValue == "",
        applied.form.field(Name).visibleErrors.nonEmpty
      )
    },
    test("keyed repeated-field blur resolves the retained row key") {
      val compiled = RenderProgram.compile[BasketDefinition.Form, BasketMsg] { source =>
        val binding = source.bind(
          DomRef("basket"),
          BasketMsg.Updated(_),
          BasketMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        binding.render(
          binding.rows(ItemRows).splitBy(_.key) { (key, _) =>
            val control = binding.field(ItemRows, key, ItemName)
            div(control.text(), button(idAttr := s"blur-${key.value}", on.click(control.blurred)))
          }
        )
      }
      val initial = BasketDefinition.initial(
        ItemRows.initial(
          ItemRows.row(RowA)(ItemName.initial("")),
          ItemRows.row(RowB)(ItemName.initial("ok"))
        )
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(initial)
        blurId = bindingOnId(candidate, "blur-row_a", "phx-click")
        update = candidate.bindings
                   .resolve(blurId).get
                   .dispatch(BindingPayload.Params(Map.empty)).toOption.collect {
                     case BindingDispatch.Owner(BasketMsg.Updated(value)) => value
                   }.get
        latest      = initial.movedAfter(ItemRows, RowA, RowB)
        applied     = update.applyTo(latest)
        rowAAddress = latest.rows(ItemRows).find(_.key == RowA).get.bind(ItemName).address
      yield assertTrue(
        applied.target.contains(rowAAddress),
        applied.form.interaction.isBlurred(rowAAddress),
        applied.form
          .field(applied.form.rows(ItemRows).find(_.key == RowA).get.bind(ItemName))
          .visibleErrors.nonEmpty
      )
    },
    test("optional bound field disappears safely when its row is removed") {
      val initial = BasketDefinition.initial(
        ItemRows.initial(ItemRows.row(RowA)(ItemName.initial("row-a-value")))
      )
      val bound    = initial.rows(ItemRows).head.bind(ItemName)
      val compiled = RenderProgram.compile[BasketDefinition.Form, BasketMsg] { source =>
        val binding = source.bind(DomRef("basket"), BasketMsg.Updated(_), BasketMsg.Submitted(_))
        binding.render(binding.optionalField(bound)(_.text()))
      }

      for
        program <- ZIO.fromEither(compiled)
        before  <- program.evaluate(initial)
        after   <- program.evaluate(initial.removed(ItemRows, RowA), Some(before.commit))
        beforeHtml = HtmlRenderer.render(before.tree)
        afterHtml  = HtmlRenderer.render(after.tree)
      yield assertTrue(
        beforeHtml.contains("row-a-value"),
        beforeHtml.contains(bound.path.name),
        !afterHtml.contains("row-a-value"),
        !afterHtml.contains(bound.path.name)
      )
    },
    test("optional keyed field disappears safely when its row is removed") {
      val initial = BasketDefinition.initial(
        ItemRows.initial(ItemRows.row(RowA)(ItemName.initial("row-a-value")))
      )
      val compiled = RenderProgram.compile[BasketDefinition.Form, BasketMsg] { source =>
        val binding = source.bind(DomRef("basket"), BasketMsg.Updated(_), BasketMsg.Submitted(_))
        binding.render(binding.optionalField(ItemRows, RowA, ItemName)(_.text()))
      }

      for
        program <- ZIO.fromEither(compiled)
        before  <- program.evaluate(initial)
        after   <- program.evaluate(initial.removed(ItemRows, RowA), Some(before.commit))
        beforeHtml = HtmlRenderer.render(before.tree)
        afterHtml  = HtmlRenderer.render(after.tree)
      yield assertTrue(
        beforeHtml.contains("row-a-value"),
        beforeHtml.contains("basket[items][row_a][name]"),
        !afterHtml.contains("row-a-value"),
        !afterHtml.contains("basket[items][row_a][name]")
      )
    },
    test("bound field renders and rejects a foreign definition even when cast") {
      val initial = BasketDefinition.initial(
        ItemRows.initial(ItemRows.row(RowA)(ItemName.initial("owned-value")))
      )
      val bound = initial.rows(ItemRows).head.bind(ItemName)

      val OtherRoot       = FormRoot("basket")
      val OtherItems      = OtherRoot.rows("items")
      val OtherName       = OtherItems.text("name").required(FieldIssue("Item name is required"))
      val OtherRows       = OtherItems.product[Item](Tuple1(OtherName))
      val OtherDefinition = OtherRoot.product[Basket](Tuple1(OtherRows))
      val otherInitial    = OtherDefinition.initial(
        OtherRows.initial(
          OtherRows.row(FormRowKey.from[OtherItems.type]("row_a").toOption.get)(
            OtherName.initial("foreign")
          )
        )
      )
      type BasketNameField =
        BoundFormField[BasketRoot.type, BasketDefinition.Schema, Items.type, String, String]
      val foreign = otherInitial.rows(OtherRows).head.bind(OtherName).asInstanceOf[BasketNameField]

      def compile(field: BasketNameField) =
        RenderProgram.compile[BasketDefinition.Form, BasketMsg] { source =>
          val binding = source.bind(DomRef("basket"), BasketMsg.Updated(_), BasketMsg.Submitted(_))
          binding.render(binding.field(field).text())
        }

      for
        ownedProgram   <- ZIO.fromEither(compile(bound))
        owned          <- ownedProgram.evaluate(initial)
        foreignProgram <- ZIO.fromEither(compile(foreign))
        foreignExit    <- foreignProgram.evaluate(initial).exit
      yield assertTrue(
        HtmlRenderer.render(owned.tree).contains("owned-value"),
        foreignExit.isFailure
      )
    },
    test("signal form dispatch preserves target metadata and recovers its effective kind") {
      val compiled = profileProgram(FormFeedback.AfterBlur)
      val data     = FormData(Vector(Name.name -> "next"))
      val target   = FormPath.parse(Name.name).toOption.get

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("current")))
        changeId = rootBinding(candidate, "phx-change")
        changed  = dispatchUpdate(
                    candidate,
                    changeId,
                    BindingPayload.Form(
                      data,
                      RawFormEvent.Meta(
                        target = Some(target),
                        metadata = Map(FormUpdate.BlurMetadataKey -> Name.name)
                      )
                    )
                  ).applyTo(ProfileDefinition.initial(Name.initial("current")))
        recovered = dispatchUpdate(
                      candidate,
                      changeId,
                      BindingPayload.Form(data, RawFormEvent.Meta(recovery = true))
                    ).applyTo(ProfileDefinition.initial(Name.initial("current")))
      yield assertTrue(
        changed.kind == FormUpdateKind.Blurred,
        changed.target.contains(Name.address),
        changed.form.field(Name).fieldValue == "next",
        recovered.kind == FormUpdateKind.Recovered
      )
    },
    test("submission dispatches a typed event with submission feedback policy") {
      val compiled = profileProgram(FormFeedback.AfterBlur)

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("current")))
        submitId  = rootBinding(candidate, "phx-submit")
        submitted = candidate.bindings
                      .resolve(submitId).get
                      .dispatch(BindingPayload.Form(FormData(Vector(Name.name -> "Ada"))))
                      .toOption.collect { case BindingDispatch.Owner(ProfileMsg.Submitted(event)) =>
                        event
                      }.get
      yield assertTrue(
        submitted.kind == FormEventKind.Submitted,
        submitted.valueOption.contains(Profile("Ada")),
        submitted.form.interaction.visibility == ErrorVisibility.All,
        submitted.form.interaction.feedback == FormFeedback.AfterBlur
      )
    },
    test("submission preserves previously blurred field history") {
      val compiled = profileProgram(FormFeedback.AfterBlur)
      val initial  = ProfileDefinition.initial(Name.initial("current"))
      val blurred  = FormUpdate
        .blurred[ProfileRoot.type, ProfileDefinition.Schema, Profile, String, String](
          Name,
          FormFeedback.AfterBlur
        )
        .applyTo(initial).form

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(blurred)
        submitId  = rootBinding(candidate, "phx-submit")
        submitted = candidate.bindings
                      .resolve(submitId).get
                      .dispatch(BindingPayload.Form(FormData(Vector(Name.name -> "Ada"))))
                      .toOption.collect { case BindingDispatch.Owner(ProfileMsg.Submitted(event)) =>
                        event
                      }.get
      yield assertTrue(
        submitted.valueOption.contains(Profile("Ada")),
        submitted.form.interaction.isBlurred(Name.address)
      )
    },
    test("onBlur compiles and dispatches its custom value handler") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(
          DomRef("profile"),
          ProfileMsg.Updated(_),
          ProfileMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        binding.render(binding.field(Name).onBlur(ProfileMsg.BlurValue(_)).text())
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("Ada")))
        handlerId = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        result    = candidate.bindings
                   .resolve(handlerId).get.dispatch(
                     BindingPayload.Params(Map("value" -> "from-browser"))
                   )
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        html.contains("&quot;push&quot;"),
        !html.contains("data-scalive-blur-handler="),
        result == Right(BindingDispatch.Owner(ProfileMsg.BlurValue("from-browser")))
      )
    },
    test("WhenUsed onBlur pushes only and supplies an empty missing value") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(binding.field(Name).onBlur(ProfileMsg.BlurValue(_)).text())
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("Ada")))
        handlerId = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        result    = candidate.bindings
                   .resolve(handlerId).get.dispatch(BindingPayload.Params(Map.empty))
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        html.contains("&quot;push&quot;"),
        !html.contains("&quot;dispatch&quot;"),
        !html.contains("profile-scalive-blur-trigger"),
        result == Right(BindingDispatch.Owner(ProfileMsg.BlurValue("")))
      )
    }
  )

  private def profileProgram(feedback: FormFeedback = FormFeedback.WhenUsed) =
    RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { sourceSignal =>
      val binding = sourceSignal.bind(
        DomRef("profile"),
        onUpdate = ProfileMsg.Updated(_),
        onSubmit = ProfileMsg.Submitted(_),
        feedback = feedback
      )
      val control = binding.field(Name)
      binding.render(control.text(control.validationAttributes))
    }

  private def consentProgram() =
    RenderProgram.compile[ConsentDefinition.Form, ConsentMsg] { source =>
      val binding = source.bind(DomRef("consent"), ConsentMsg.Updated(_), ConsentMsg.Submitted(_))
      val control = binding.field(Accepted)
      binding.render(
        control.checkbox(control.validationAttributes),
        control.errorFeedback(_.map(_.issue.code.getOrElse("")))
      )
    }

  private def dispatchUpdate(
    candidate: RenderCandidate[ProfileMsg],
    id: BindingId,
    payload: BindingPayload
  ): ProfileDefinition.Update =
    candidate.bindings
      .resolve(id).get.dispatch(payload).toOption.collect {
        case BindingDispatch.Owner(ProfileMsg.Updated(update)) => update
      }.get

  private def rootBinding(candidate: RenderCandidate[?], name: String): BindingId =
    attribute(candidate.tree.root, name)

  private def bindingOnId(
    candidate: RenderCandidate[?],
    elementId: String,
    name: String
  ): BindingId =
    elements(candidate.tree.root)
      .find(element => textAttribute(element, "id").contains(elementId))
      .flatMap(element => textAttribute(element, name))
      .map(BindingId.fromEncoded)
      .getOrElse(throw IllegalStateException(s"Missing $name on #$elementId"))

  private def attribute(element: EvaluatedNode.Element, name: String): BindingId =
    textAttribute(element, name)
      .map(BindingId.fromEncoded)
      .getOrElse(throw IllegalStateException(s"Missing $name binding attribute"))

  private def textAttribute(element: EvaluatedNode.Element, name: String): Option[String] =
    element.attributes.collectFirst {
      case EvaluatedAttribute(`name`, Some(AttributeValue.Text(value)), _, _) => value
    }

  private def elements(node: EvaluatedNode): Vector[EvaluatedNode.Element] = node match
    case element: EvaluatedNode.Element => element +: element.children.flatMap(elements)
    case keyed: EvaluatedNode.Keyed     => keyed.rows.flatMap(row => elements(row.child))
    case choice: EvaluatedNode.Choice   => choice.child.toVector.flatMap(elements)
    case flash: EvaluatedNode.Flash     => flash.child.toVector.flatMap(elements)
    case _                              => Vector.empty
end FormBindingSpec
