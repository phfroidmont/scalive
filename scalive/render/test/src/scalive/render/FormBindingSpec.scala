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
    test("tel, date and search preserve scoped identity, raw values and explicit ARIA") {
      val cases = Vector(
        ("tel", " +32 0123 456 ", "  +44 020 1234  "),
        ("date", "2026-02-30", " 2026-13-40 "),
        ("search", "  Ada Lovelace  ", " Grace Hopper ")
      )

      ZIO
        .foreach(cases) { case (inputType, rawValue, afterRawValue) =>
          val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
            val first = source.bind(DomRef("first"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
            val second =
              source.bind(DomRef("second"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
            val firstName  = first.field(Name)
            val secondName = second.field(Name)
            val plain      = inputType match
              case "tel" =>
                firstName.tel(cls := "caller-control", dataAttr("scope") := "name")
              case "date" =>
                firstName.date(cls := "caller-control", dataAttr("scope") := "name")
              case _ =>
                firstName.search(cls := "caller-control", dataAttr("scope") := "name")
            val accessible = inputType match
              case "tel" =>
                secondName.tel(
                  secondName.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
              case "date" =>
                secondName.date(
                  secondName.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
              case _ =>
                secondName.search(
                  secondName.validationAttributes,
                  cls               := "caller-control",
                  dataAttr("scope") := "name"
                )
            div(first.render(plain), second.render(accessible))
          }

          for
            program   <- ZIO.fromEither(compiled)
            candidate <- program.evaluate(ProfileDefinition.initial(Name.initial(rawValue)))
            after     <- program.evaluate(
                       ProfileDefinition.initial(Name.initial(afterRawValue)),
                       Some(candidate.commit)
                     )
            html      = HtmlRenderer.render(candidate.tree)
            afterHtml = HtmlRenderer.render(after.tree)
            firstId   = s"first-${Name.address.id}"
            secondId  = s"second-${Name.address.id}"
          yield assertTrue(
            html.contains(
              s"<input type=\"$inputType\" id=\"$firstId\" name=\"profile[name]\" value=\"$rawValue\" class=\"caller-control\" data-scope=\"name\">"
            ),
            html.contains(
              s"<input type=\"$inputType\" id=\"$secondId\" name=\"profile[name]\" value=\"$rawValue\" aria-describedby=\"${secondId}_errors\" class=\"caller-control\" data-scope=\"name\">"
            ),
            afterHtml.contains(
              s"<input type=\"$inputType\" id=\"$firstId\" name=\"profile[name]\" value=\"$afterRawValue\" class=\"caller-control\" data-scope=\"name\">"
            ),
            afterHtml.contains(
              s"<input type=\"$inputType\" id=\"$secondId\" name=\"profile[name]\" value=\"$afterRawValue\" aria-describedby=\"${secondId}_errors\" class=\"caller-control\" data-scope=\"name\">"
            )
          )
        }.map(_.reduce(_ && _))
    },
    test("tel, date and search reject caller type overrides") {
      val compiled = Vector("tel", "date", "search").map { inputType =>
        RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
          val binding =
            source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
          val control = binding.field(Name)
          val input   = inputType match
            case "tel"  => control.tel(typ := "text")
            case "date" => control.date(typ := "text")
            case _      => control.search(typ := "text")
          binding.render(input)
        }
      }

      assertTrue(compiled.forall(_.isLeft))
    },
    test("tel uses WhenUsed by default without a blur handler or trigger") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        binding.render(binding.field(Name).tel())
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial(" +32 0123 456 ")))
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        candidate.bindings.size == 3,
        !candidate.bindings.ids.exists(_.encoded.startsWith("j")),
        html.contains("type=\"tel\""),
        !html.contains("phx-blur="),
        !html.contains("profile-scalive-blur-trigger")
      )
    },
    test("date AfterBlur uses the shared ordered feedback commands") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(
          DomRef("profile"),
          ProfileMsg.Updated(_),
          ProfileMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        binding.render(binding.field(Name).date())
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("2026-02-30")))
        html          = HtmlRenderer.render(candidate.tree)
        setIndex      = html.indexOf("&quot;set_attr&quot;")
        dispatchIndex = html.indexOf("&quot;dispatch&quot;")
        removeIndex   = html.indexOf("&quot;remove_attr&quot;")
      yield assertTrue(
        html.contains("type=\"date\""),
        html.contains("value=\"2026-02-30\""),
        html.contains("id=\"profile-scalive-blur-trigger\""),
        html.contains("phx-blur="),
        html.contains("phx-value-scalive-blur"),
        html.contains(s"&quot;${Name.name}&quot;"),
        html.contains("#profile-scalive-blur-trigger"),
        setIndex >= 0,
        setIndex < dispatchIndex,
        dispatchIndex < removeIndex,
        !html.contains("&quot;push&quot;")
      )
    },
    test("search AfterBlur orders feedback before its custom value handler") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(
          DomRef("profile"),
          ProfileMsg.Updated(_),
          ProfileMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        binding.render(binding.field(Name).onBlur(ProfileMsg.BlurValue(_)).search())
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial(Name.initial("rendered query")))
        handlerId = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        result    = candidate.bindings
                   .resolve(handlerId).get.dispatch(
                     BindingPayload.Params(Map("value" -> "  browser query  "))
                   )
        html          = HtmlRenderer.render(candidate.tree)
        setIndex      = html.indexOf("&quot;set_attr&quot;")
        dispatchIndex = html.indexOf("&quot;dispatch&quot;")
        removeIndex   = html.indexOf("&quot;remove_attr&quot;")
        pushIndex     = html.indexOf("&quot;push&quot;")
      yield assertTrue(
        html.contains("type=\"search\""),
        html.contains("phx-blur="),
        html.contains("id=\"profile-scalive-blur-trigger\""),
        setIndex >= 0,
        setIndex < dispatchIndex,
        dispatchIndex < removeIndex,
        removeIndex < pushIndex,
        result == Right(BindingDispatch.Owner(ProfileMsg.BlurValue("  browser query  ")))
      )
    },
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
    test("WhenUsed text rejects conditional blur handlers") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val control = binding.field(Name)
        binding.render(
          control.text(
            control.hasVisibleErrors.chooseMod(
              on.blur(ProfileMsg.BlurValue("invalid")),
              on.blur(ProfileMsg.BlurValue("valid"))
            )
          )
        )
      }

      assertTrue(compiled.left.exists {
        case RenderError.EvaluationFailed(error: IllegalArgumentException) =>
          error.getMessage.contains("form binding owns attributes: phx-blur")
        case _ => false
      })
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
    test("checkbox item ids encode UTF-16 keys without collisions and keep exact parent names") {
      val keys = Vector(
        ""             -> "",
        "a"            -> "0061",
        "a-b"          -> "0061002d0062",
        "a_b"          -> "0061005f0062",
        "a b"          -> "006100200062",
        "a.b"          -> "0061002e0062",
        "[]"           -> "005b005d",
        "?"            -> "003f",
        "\u0000"       -> "0000",
        "\u00e9"       -> "00e9",
        "e\u0301"      -> "00650301",
        "\uD83D\uDE00" -> "d83dde00",
        "\uD800"       -> "d800",
        "\\uD800"      -> "005c00750044003800300030",
        "name"         -> "006e0061006d0065",
        "name_errors"  -> "006e0061006d0065005f006500720072006f00720073"
      )
      val root       = FormRoot("choices")
      val values     = root.texts("values")
      val other      = root.texts("values_errors")
      val definition = root.product[(Vector[String], Vector[String])]((values, other))
      val fields     = Vector(values, other)
      val compiled   = RenderProgram.compile[definition.Form, Unit] { source =>
        div(Vector("first", "second").map { instance =>
          val binding = source.bind(DomRef(instance), _ => (), _ => ())
          binding.render(fields.map { field =>
            val control = binding.field(field)
            div(
              dataAttr("parent-id")       := control.id,
              dataAttr("parent-name")     := control.name,
              dataAttr("parent-error-id") := control.errorId,
              keys.zipWithIndex.map { case ((key, _), index) =>
                val item = control.item(key)
                div(
                  label(forId                                         := item.id, s"Choice $index"),
                  item.checkbox("shared-token", dataAttr("item-name") := item.name),
                  // Recreating a handle is stable; duplicate rendered ids remain the caller's job.
                  dataAttr("same-key-id") := control.item(key).id
                )
              }
            )
          })
        })
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(definition.initial())
        all      = elements(candidate.tree.root)
        inputs   = checkboxes(candidate)
        expected =
          for
            instance <- Vector("first", "second")
            field    <- fields
            (_, hex) <- keys
          yield (s"$instance-${field.address.id}_item_$hex", field.name)
        ids  = inputs.flatMap(textAttribute(_, "id"))
        html = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        ids == expected.map(_._1),
        ids.distinct.size == ids.size,
        inputs.flatMap(textAttribute(_, "name")) == expected.map(_._2),
        inputs.flatMap(textAttribute(_, "data-item-name")) == expected.map(_._2),
        expected.map(_._2).toSet == Set("choices[values]", "choices[values_errors]"),
        all.filter(_.tag == "label").flatMap(textAttribute(_, "for")) == ids,
        all.flatMap(textAttribute(_, "data-same-key-id")) == ids,
        Vector("first", "second").forall { instance =>
          fields.forall { field =>
            val parentId = s"$instance-${field.address.id}"
            all.exists { element =>
              textAttribute(element, "data-parent-id").contains(parentId) &&
              textAttribute(element, "data-parent-name").contains(field.name) &&
              textAttribute(element, "data-parent-error-id").contains(s"${parentId}_errors")
            } && !ids.contains(parentId) && !ids.contains(s"${parentId}_errors")
          }
        },
        expected.zipWithIndex.forall { case ((id, _), index) =>
          html.contains(
            s"<label for=\"$id\">Choice ${index % keys.size}</label>"
          )
        }
      )
      end for
    },
    test("checkbox items reject null keys at handle construction") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val _       = binding.field(Name).item(null)
        binding.render()
      }

      assertTrue(compiled.left.exists {
        case RenderError.EvaluationFailed(_: IllegalArgumentException) => true
        case _                                                         => false
      })
    },
    test("checkbox items share parent raw values, metadata, and opt-in validation feedback") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val control = binding.field(Name)
        binding.render(
          dataAttr("parent-id")       := control.id,
          dataAttr("parent-name")     := control.name,
          dataAttr("parent-error-id") := control.errorId,
          dataAttr("raw-values")      := control.rawValues.map(_.mkString("|")),
          dataAttr("last-value")      := control.fieldValue,
          dataAttr("error-count")     := control.visibleErrors.map(_.size.toString),
          control.item("first").checkbox("a", control.validationAttributes),
          control.item("second").checkbox("b", control.validationAttributes),
          control.item("plain").checkbox("c"),
          control.errorFeedback(_.map(_.message))
        )
      }
      val initial = ProfileDefinition.initial().pristine
      val invalid = initial.updatedRaw(Name, Vector("a", "b")).withAllErrorsVisible

      for
        program <- ZIO.fromEither(compiled)
        before  <- program.evaluate(initial)
        after   <- program.evaluate(invalid, Some(before.commit))
        inputs   = checkboxes(after)
        all      = elements(after.tree.root)
        parentId = s"profile-${Name.address.id}"
      yield assertTrue(
        invalid.field(Name).input.isLeft,
        invalid.errors.all.map(_.address) == Vector(Name.address),
        inputs.map(isChecked) == Vector(true, true, false),
        checkboxes(before).forall(textAttribute(_, "aria-invalid").isEmpty),
        inputs.take(2).forall(textAttribute(_, "aria-invalid").contains("true")),
        inputs.take(2).forall(textAttribute(_, "aria-describedby").contains(s"${parentId}_errors")),
        textAttribute(inputs.last, "aria-invalid").isEmpty,
        textAttribute(inputs.last, "aria-describedby").isEmpty,
        all.count(textAttribute(_, "id").contains(s"${parentId}_errors")) == 1,
        all.count(textAttribute(_, "class").contains("form-error")) == 1,
        all
          .find(textAttribute(_, "id").contains(s"${parentId}_errors"))
          .flatMap(textAttribute(_, "phx-feedback-for")).contains("profile[name]"),
        textAttribute(after.tree.root, "data-parent-id").contains(parentId),
        textAttribute(after.tree.root, "data-parent-name").contains(Name.name),
        textAttribute(after.tree.root, "data-parent-error-id").contains(s"${parentId}_errors"),
        textAttribute(after.tree.root, "data-raw-values").contains("a|b"),
        textAttribute(after.tree.root, "data-last-value").contains("b"),
        textAttribute(after.tree.root, "data-error-count").contains("1"),
        after.bindings.size == 3,
        inputs.forall(textAttribute(_, "phx-blur").isEmpty),
        !HtmlRenderer.render(after.tree).contains("type=\"hidden\"")
      )
      end for
    },
    test(
      "static and signal item tokens use raw membership for empty, duplicate, and unknown values"
    ) {
      val root       = FormRoot("choices")
      val values     = root.texts("values").map(_.map(_.toUpperCase(java.util.Locale.ROOT)))
      val definition = root.product[Tuple1[Vector[String]]](Tuple1(values))
      val tokens     = Vector("", "a", "b")
      val cases      = Vector(
        Vector.empty,
        Vector(""),
        Vector("a", "b", "a"),
        Vector("unknown"),
        Vector("a", "unknown")
      )
      val compiled = RenderProgram.compile[definition.Form, Unit] { source =>
        val binding = source.bind(DomRef("choices"), _ => (), _ => ())
        val control = binding.field(values)
        binding.render(tokens.zipWithIndex.map { (token, index) =>
          div(
            control.item(s"static-$index").checkbox(token),
            control.item(s"signal-$index").checkbox(source.map(_ => token))
          )
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        results <-
          ZIO.foreach(cases) { raw =>
            val form = definition.initial().updatedRaw(values, raw)
            program.evaluate(form).map { candidate =>
              val inputs = checkboxes(candidate)
              assertTrue(
                form.field(values).rawValues == raw,
                inputs.flatMap(textAttribute(_, "value")) == tokens.flatMap(t => Vector(t, t)),
                inputs.map(isChecked) == tokens.flatMap(t => Vector.fill(2)(raw.contains(t))),
                inputs.forall(textAttribute(_, "name").contains("choices[values]"))
              )
            }
          }
      yield results.reduce(_ && _)
    },
    test(
      "keyed checkbox items react to selection-only and token updates without changing identity"
    ) {
      final case class Choice(key: String, token: String, caption: String)
      val options        = Vector(Choice("a", "alpha", "A"), Choice("b", "beta", "B"))
      val initial        = ProfileDefinition.initial(Name.initial("alpha"))
      val selected       = initial.updatedRaw(Name, Vector("beta"))
      val changedOptions = options.updated(0, Choice("a", "beta", "A changed"))
      val compiled       =
        RenderProgram.compile[(ProfileDefinition.Form, Vector[Choice]), ProfileMsg] { source =>
          val binding = source
            .map(_._1).bind(
              DomRef("profile"),
              ProfileMsg.Updated(_),
              ProfileMsg.Submitted(_)
            )
          val control = binding.field(Name)
          binding.render(source.map(_._2).splitBy(_.key) { (key, choice) =>
            val item = control.item(key)
            div(
              item.checkbox(
                choice.map(_.token),
                on.focus(choice)((current, _) => ProfileMsg.BlurValue(current.token))
              ),
              label(forId := item.id, choice.map(_.caption))
            )
          })
        }

      for
        program    <- ZIO.fromEither(compiled)
        before     <- program.evaluate((initial, options))
        reselected <- program.evaluate((selected, options), Some(before.commit))
        retokened  <- program.evaluate((selected, changedOptions), Some(reselected.commit))
        reordered  <- program.evaluate((selected, changedOptions.reverse), Some(retokened.commit))
        removed    <- program.evaluate((selected, changedOptions.take(1)), Some(reordered.commit))
        beforeInputs  = checkboxes(before)
        ids           = beforeInputs.flatMap(textAttribute(_, "id"))
        firstBinding  = bindingOnId(before, ids.head, "phx-focus")
        secondBinding = bindingOnId(before, ids.last, "phx-focus")
        beforeRows    = before.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
        reorderedRows = reordered.tree.root.children.head.asInstanceOf[EvaluatedNode.Keyed].rows
      yield assertTrue(
        beforeInputs.map(isChecked) == Vector(true, false),
        checkboxes(reselected).map(isChecked) == Vector(false, true),
        checkboxes(retokened).map(isChecked) == Vector(true, true),
        checkboxes(retokened).flatMap(textAttribute(_, "value")) == Vector("beta", "beta"),
        checkboxes(reselected).flatMap(textAttribute(_, "id")) == ids,
        checkboxes(retokened).flatMap(textAttribute(_, "id")) == ids,
        checkboxes(reordered).flatMap(textAttribute(_, "id")) == ids.reverse,
        beforeRows.map(_.id) == reorderedRows.reverse.map(_.id),
        before.bindings.ids.toSet == reordered.bindings.ids.toSet,
        elements(retokened.tree.root)
          .filter(_.tag == "label").flatMap(textAttribute(_, "for")) == ids,
        HtmlRenderer
          .render(retokened.tree).contains(s"<label for=\"${ids.head}\">A changed</label>"),
        checkboxes(removed).flatMap(textAttribute(_, "id")) == ids.take(1),
        removed.bindings.resolve(secondBinding).isEmpty,
        removed.bindings.resolve(firstBinding).get.dispatch(BindingPayload.Params(Map.empty)) ==
          Right(BindingDispatch.Owner(ProfileMsg.BlurValue("beta")))
      )
      end for
    },
    test("checkbox items nested in repeated rows retain scoped names and retire with their row") {
      val initial = BasketDefinition.initial(
        ItemRows.initial(
          ItemRows.row(RowA)(ItemName.initial("a")),
          ItemRows.row(RowB)(ItemName.initial("b"))
        )
      )
      val changed = initial
        .updated(initial.rows(ItemRows).head.bind(ItemName), "b")
        .movedBefore(ItemRows, RowB, RowA)
      val compiled = RenderProgram.compile[BasketDefinition.Form, BasketMsg] { source =>
        val binding = source.bind(
          DomRef("basket"),
          BasketMsg.Updated(_),
          BasketMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        binding.render(binding.rows(ItemRows).splitBy(_.key) { (key, row) =>
          val control = binding.field(ItemRows, key, ItemName)
          div(
            row.presence(),
            row.map(_ => Vector("a", "b")).splitBy(identity) { (choiceKey, token) =>
              val item = control.item(choiceKey)
              div(
                item.checkbox(token, control.validationAttributes),
                label(forId   := item.id, token),
                button(idAttr := s"blur-${key.value}-$choiceKey", on.click(control.blurred))
              )
            }
          )
        })
      }

      for
        program <- ZIO.fromEither(compiled)
        before  <- program.evaluate(initial)
        outerRows = before.tree.root.children.collectFirst { case keyed: EvaluatedNode.Keyed =>
                      keyed.rows
                    }.get
        nestedA = outerRows.head.child.children.collectFirst { case keyed: EvaluatedNode.Keyed =>
                    keyed.rows
                  }.get
        nestedB = outerRows.last.child.children.collectFirst { case keyed: EvaluatedNode.Keyed =>
                    keyed.rows
                  }.get
        scopesA = (outerRows.head.id +: nestedA.map(_.id)).map(before.newRowScopes.apply)
        scopesB = (outerRows.last.id +: nestedB.map(_.id)).map(before.newRowScopes.apply)
        reordered <- program.evaluate(changed, Some(before.commit))
        blurId = bindingOnId(reordered, "blur-row_a-a", "phx-click")
        update =
          reordered.bindings
            .resolve(blurId).get.dispatch(BindingPayload.Params(Map.empty))
            .toOption.collect { case BindingDispatch.Owner(BasketMsg.Updated(value)) => value }.get
        rowAAddress = changed.rows(ItemRows).find(_.key == RowA).get.bind(ItemName).address
        removed <- program.evaluate(changed.removed(ItemRows, RowA), Some(reordered.commit))
        committed = removed.commit
        ids       = checkboxes(before).flatMap(textAttribute(_, "id"))
        expected  = initial.rows(ItemRows).flatMap { row =>
                     val parentId = s"basket-${row.bind(ItemName).address.id}"
                     Vector(s"${parentId}_item_0061", s"${parentId}_item_0062")
                   }
      yield assertTrue(
        ids == expected,
        ids.distinct.size == 4,
        checkboxes(before).flatMap(textAttribute(_, "name")) == Vector(
          "basket[items][row_a][name]",
          "basket[items][row_a][name]",
          "basket[items][row_b][name]",
          "basket[items][row_b][name]"
        ),
        elements(before.tree.root).filter(_.tag == "label").flatMap(textAttribute(_, "for")) == ids,
        checkboxes(before).map(isChecked) == Vector(true, false, false, true),
        checkboxes(reordered).map(isChecked) == Vector(false, true, false, true),
        checkboxes(reordered).flatMap(textAttribute(_, "id")) == ids.drop(2) ++ ids.take(2),
        update.applyTo(changed).target.contains(rowAAddress),
        update.applyTo(changed).form.interaction.isBlurred(rowAAddress),
        checkboxes(removed).flatMap(textAttribute(_, "id")) == ids.drop(2),
        !HtmlRenderer.render(committed.tree).contains("basket[items][row_a][name]"),
        removed.bindings.resolve(blurId).isEmpty,
        scopesA.forall(_.isClosed),
        scopesB.forall(scope => !scope.isClosed)
      )
      end for
    },
    test(
      "checkbox items reject owned attributes case-insensitively through groups and conditionals"
    ) {
      val names   = Vector("id", "name", "value", "type", "checked", "phx-change", "phx-blur")
      val results = for
        name  <- names.flatMap(name => Vector(name, name.toUpperCase(java.util.Locale.ROOT)))
        shape <- Vector("direct", "group", "conditional", "nested")
      yield
        val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
          val binding =
            source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
          val control  = binding.field(Name)
          val attr     = Mod.Attr.Static(name, "override")
          val grouped  = Mod.Attr.Group(Vector(cls := "allowed", Mod.Attr.Group(Vector(attr))))
          val modifier = shape match
            case "direct"      => attr
            case "group"       => grouped
            case "conditional" => control.hasVisibleErrors.chooseMod(attr, attr)
            case _             =>
              control.hasVisibleErrors.chooseMod(
                cls := "allowed",
                control.hasVisibleErrors.chooseMod(cls := "allowed", grouped)
              )
          binding.render(control.item("choice").checkbox("token", modifier))
        }
        compiled.left.exists {
          case RenderError.EvaluationFailed(_: IllegalArgumentException) => true
          case _                                                         => false
        }

      assertTrue(results.forall(identity))
    },
    test("checkbox items allow ordinary attributes, modifier collections, and focus messages") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val control = binding.field(Name)
        binding.render(
          control
            .item("choice").checkbox(
              "token",
              Vector(cls   := "choice", dataAttr("hint") := "allowed"),
              Option(title := "A choice"),
              required   := true,
              aria.label := "Choose token",
              control.hasVisibleErrors.chooseMod(disabled := true, disabled := false),
              on.focus.withValue(ProfileMsg.BlurValue(_))
            )
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial().pristine)
        input = checkboxes(candidate).head
        focus = attribute(input, "phx-focus")
      yield assertTrue(
        textAttribute(input, "class").contains("choice"),
        textAttribute(input, "data-hint").contains("allowed"),
        textAttribute(input, "title").contains("A choice"),
        textAttribute(input, "aria-label").contains("Choose token"),
        input.attributes.exists(attr =>
          attr.name == "required" && attr.value.contains(AttributeValue.Presence)
        ),
        !input.attributes.exists(attr => attr.name == "disabled" && attr.value.nonEmpty),
        candidate.bindings
          .resolve(focus).get.dispatch(BindingPayload.Params(Map("value" -> "browser"))) ==
          Right(BindingDispatch.Owner(ProfileMsg.BlurValue("browser")))
      )
    },
    test("checkbox items inherit prior onBlur handlers after parent-field feedback dispatch") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(
          DomRef("profile"),
          ProfileMsg.Updated(_),
          ProfileMsg.Submitted(_),
          FormFeedback.AfterBlur
        )
        val control = binding.field(Name).onBlur(ProfileMsg.BlurValue(_))
        binding.render(control.item("choice").checkbox("token"))
      }
      val initial = ProfileDefinition.initial(Name.initial("token"))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(initial)
        handler       = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        html          = HtmlRenderer.render(candidate.tree)
        setIndex      = html.indexOf("&quot;set_attr&quot;")
        dispatchIndex = html.indexOf("&quot;dispatch&quot;")
        removeIndex   = html.indexOf("&quot;remove_attr&quot;")
        pushIndex     = html.indexOf("&quot;push&quot;")
        update        = dispatchUpdate(
                   candidate,
                   rootBinding(candidate, "phx-change"),
                   BindingPayload.Form(
                     FormData(Vector(Name.name -> "")),
                     RawFormEvent.Meta(metadata = Map(FormUpdate.BlurMetadataKey -> Name.name))
                   )
                 )
        applied = update.applyTo(initial)
      yield assertTrue(
        html.contains("phx-blur="),
        html.contains("#profile-scalive-blur-trigger"),
        html.contains(s"&quot;${Name.name}&quot;"),
        setIndex >= 0,
        setIndex < dispatchIndex,
        dispatchIndex < removeIndex,
        removeIndex < pushIndex,
        candidate.bindings
          .resolve(handler).get.dispatch(BindingPayload.Params(Map("value" -> "browser-token"))) ==
          Right(BindingDispatch.Owner(ProfileMsg.BlurValue("browser-token"))),
        candidate.bindings.resolve(handler).get.dispatch(BindingPayload.Params(Map.empty)) ==
          Right(BindingDispatch.Owner(ProfileMsg.BlurValue(""))),
        applied.kind == FormUpdateKind.Blurred,
        applied.target.contains(Name.address),
        applied.form.field(Name).visibleErrors.map(_.message) == Vector("Name is required")
      )
      end for
    },
    test("WhenUsed checkbox items inherit message-only blur handlers without feedback commands") {
      val compiled = RenderProgram.compile[ProfileDefinition.Form, ProfileMsg] { source =>
        val binding = source.bind(DomRef("profile"), ProfileMsg.Updated(_), ProfileMsg.Submitted(_))
        val control = binding.field(Name).onBlur(ProfileMsg.BlurValue("fixed"))
        binding.render(control.item("choice").checkbox("token"))
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(ProfileDefinition.initial())
        handler = candidate.bindings.ids.find(_.encoded.startsWith("j")).get
        html    = HtmlRenderer.render(candidate.tree)
      yield assertTrue(
        html.contains("phx-blur="),
        html.contains("&quot;push&quot;"),
        !html.contains("&quot;dispatch&quot;"),
        !html.contains("profile-scalive-blur-trigger"),
        candidate.bindings.resolve(handler).get.dispatch(BindingPayload.Params(Map.empty)) ==
          Right(BindingDispatch.Owner(ProfileMsg.BlurValue("fixed")))
      )
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

  private def checkboxes(candidate: RenderCandidate[?]): Vector[EvaluatedNode.Element] =
    elements(candidate.tree.root).filter(textAttribute(_, "type").contains("checkbox"))

  private def isChecked(element: EvaluatedNode.Element): Boolean =
    element.attributes.exists(attr =>
      attr.name == "checked" && attr.value.contains(AttributeValue.Presence)
    )

  private def elements(node: EvaluatedNode): Vector[EvaluatedNode.Element] = node match
    case element: EvaluatedNode.Element => element +: element.children.flatMap(elements)
    case keyed: EvaluatedNode.Keyed     => keyed.rows.flatMap(row => elements(row.child))
    case choice: EvaluatedNode.Choice   => choice.child.toVector.flatMap(elements)
    case flash: EvaluatedNode.Flash     => flash.child.toVector.flatMap(elements)
    case _                              => Vector.empty
end FormBindingSpec
