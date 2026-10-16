package scalive.testing

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import org.jsoup.Jsoup
import zio.*
import zio.http.{Request, URL}
import zio.http.codec.PathCodec
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.test.*

import scalive.*

object ConnectedRenderSpec extends ZIOSpecDefault:
  private enum Msg:
    case Increment

  private enum FormMsg:
    case Changed(event: RawFormEvent[String])
    case Submitted(event: RawFormEvent[String])

  private object TypedSubmitForm:
    final case class Profile(name: String)
    val Root       = FormRoot("typed_profile")
    val Name       = Root.text("name")
    val Definition = Root.product[Profile]((name = Name))

    enum Intent(val wireValue: String):
      case Preview extends Intent("preview")
      case Save    extends Intent("save")

    val Submitter = Definition.submitter(Intent.values)(_.wireValue)

    enum Msg:
      case Submitted(
        event: Definition.Event,
        intent: Either[FormSubmitter.DecodeError, Intent])

  private object SignupForm:
    final case class Signup(name: String, email: String, newsletter: Boolean)
    val Root       = FormRoot("signup")
    val Name       = Root.text("name").required(FieldIssue("Enter your name."))
    val Email      = Root.text("email").validate(FieldIssue("Enter an email."))(_.contains("@"))
    val Newsletter = Root.field("newsletter", FieldInput.checkbox())
    val Definition =
      Root.product[Signup]((name = Name, email = Email, newsletter = Newsletter))
    val Save = DomRef("save-signup")

    enum Msg:
      case Changed(event: Definition.Event)
      case Submitted(event: Definition.Event)
      case Saved

    final case class Model(form: Definition.Form, saved: Option[Signup], clicks: Int)

    class SignupView extends LiveView[Msg, Model]:
      def mount(ctx: MountContext) = ZIO.succeed(Model(Definition.initial(), None, 0))
      def handleMessage(model: Model, ctx: MessageContext) =
        case Msg.Changed(event)   => ZIO.succeed(model.copy(form = event.form))
        case Msg.Submitted(event) =>
          ZIO.succeed(model.copy(form = event.form, saved = event.form.result.toOption))
        case Msg.Saved => ZIO.succeed(model.copy(clicks = model.clicks + 1))
      def view(model: Signal[Model]) =
        val signup     = model.map(_.form)
        val name       = signup.field(Name)
        val email      = signup.field(Email)
        val newsletter = signup.field(Newsletter)
        div(
          form(
            Definition.onChange(Msg.Changed(_)),
            Definition.onSubmit(Msg.Submitted(_)),
            name.text(),
            name.errorFeedback(_.map(_.message), dataAttr("name-errors") := ""),
            email.text(),
            email.errorFeedback(_.map(_.message), dataAttr("email-errors") := ""),
            newsletter.checkbox()
          ),
          button(Save.attr, on.click(Msg.Saved), "Save"),
          span(dataAttr("clicks") := "", model.map(_.clicks.toString)),
          span(dataAttr("saved") := "", model.map(_.saved.fold("")(_.toString)))
        )

    val Billing  = DomRef("billing")
    val Shipping = DomRef("shipping")
    val Notes    = DomRef("notes")

    enum TwinMsg:
      case Updated(ref: DomRef, update: Definition.Update)
      case Submitted(ref: DomRef, event: Definition.Event)

    /** Renders the signup definition twice, as billing and shipping forms. */
    class TwinSignupView extends LiveView[TwinMsg, Map[DomRef, String]]:
      def mount(ctx: MountContext) = ZIO.succeed(Map.empty)
      def handleMessage(model: Map[DomRef, String], ctx: MessageContext) =
        case TwinMsg.Updated(ref, update) =>
          val name = update.applyTo(Definition.initial()).form.fieldView(Name).fieldValue
          ZIO.succeed(model.updated(ref, s"changed $name"))
        case TwinMsg.Submitted(ref, event) =>
          val name   = event.data.values(Name.name).lastOption.getOrElse("")
          val intent = event.data.values("intent").mkString
          ZIO.succeed(model.updated(ref, s"submitted $name $intent".trim))
      def view(model: Signal[Map[DomRef, String]]) =
        def signup(ref: DomRef) =
          val binding = model.map(_ => Definition.initial()).bind(
            ref,
            TwinMsg.Updated(ref, _),
            TwinMsg.Submitted(ref, _)
          )
          div(
            binding.render(binding.field(Name).text()),
            span(dataAttr(ref.value) := "", model.map(_.getOrElse(ref, "")))
          )
        div(
          signup(Billing),
          signup(Shipping),
          form(Notes.attr, input(nameAttr := "note"))
        )

  private enum ChildMsg:
    case Clicked

  private enum SiblingMsg:
    case Ping

  private enum TimerMsg:
    case Replaced, Kept, Cancelled

  private enum ResourceMsg:
    case Tick

  private enum TerminalMsg:
    case Trigger

  private enum NavigationMsg:
    case Patch

  private enum RoutedNavigationMsg:
    case Navigate

  private enum AdmissionMsg:
    case Increment, Patch, Navigate

  private final case class TestSessionId(value: String) derives JsonCodec
  private final case class TestClaims(sessionId: TestSessionId) derives JsonCodec
  private final case class TestUser(name: String)
  private final case class TestAuthState(
    active: Ref[Set[TestSessionId]],
    revalidations: Ref[Int])

  private val NamePath  = FormPath("profile", "name")
  private val NameCodec = FormCodec.requiredString(NamePath.name, FieldIssue("Name is required."))

  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  def spec = suite("ConnectedRenderSpec")(
    test("joins through production admission and dispatches bindings and typed messages") {
      val view = new LiveView[Msg, Int]:
        def mount(ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
          case Msg.Increment => ZIO.succeed(model + 1)
        override def view(model: Signal[Int]) =
          div(
            span(dataAttr("count") := "", model.map(_.toString)),
            button(dataAttr("increment") := "", on.click(Msg.Increment), "Increment")
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          initial   <- connected.text("[data-count]")
          _         <- connected.click("[data-increment]")
          clicked   <- connected.text("[data-count]")
          _         <- connected.send(Msg.Increment)
          sent      <- connected.text("[data-count]")
          joined    <- connected.isJoined
          _         <- connected.leave
          left      <- connected.isJoined
        yield assertTrue(initial == "0", clicked == "1", sent == "2", joined, !left)
      }
    },
    test("delivers delayed messages, replacing or cancelling them by key") {
      val replaced  = SubscriptionKey("replaced")
      val cancelled = SubscriptionKey("cancelled")
      val view      = new LiveView[TimerMsg, Vector[TimerMsg]]:
        def mount(ctx: MountContext) =
          ctx.connection
            .whenConnected { connected =>
              val timers = connected.subscriptions
              timers.sendAfter(replaced, 20.millis)(TimerMsg.Replaced) *>
                timers.sendAfter(replaced, 40.millis)(TimerMsg.Kept) *>
                timers.sendAfter(cancelled, 20.millis)(TimerMsg.Cancelled) *>
                timers.cancel(cancelled)
            }.as(Vector.empty)
        def handleMessage(model: Vector[TimerMsg], ctx: MessageContext) =
          message => ZIO.succeed(model :+ message)
        override def view(model: Signal[Vector[TimerMsg]]) =
          span(idAttr := "received", model.map(_.mkString(",")))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _         <- connected.awaitHtml("the kept timer", 3.seconds)(_.contains("Kept"))
          _         <- ZIO.sleep(100.millis)
          received  <- connected.text("#received")
        yield assertTrue(received == "Kept")
      }
    },
    test("dispatches typed form change and submit bindings") {
      val view = new LiveView[FormMsg, RawFormEvent[String] | Null]:
        def mount(ctx: MountContext) = ZIO.succeed(null)
        def handleMessage(model: RawFormEvent[String] | Null, ctx: MessageContext) =
          case FormMsg.Changed(event)   => ZIO.succeed(event)
          case FormMsg.Submitted(event) => ZIO.succeed(event)
        override def view(model: Signal[RawFormEvent[String] | Null]) =
          val event = model.map(Option(_))
          form(
            dataAttr("profile-form") := "",
            on.change.form(NameCodec)(FormMsg.Changed(_)),
            on.submit.form(NameCodec)(FormMsg.Submitted(_)),
            input(nameAttr := NamePath.name),
            span(dataAttr("target") := "", event.map(_.flatMap(_.target).fold("")(_.name))),
            span(dataAttr("submitted") := "", event.map(_.exists(_.submitted).toString)),
            span(dataAttr("used") := "", event.map(_.exists(_.state.isUsed(NamePath)).toString))
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.changeForm(
                 "[data-profile-form]",
                  Vector(NamePath.name -> "", "profile[_unused_email]" -> ""),
                  target = Some(NamePath.name)
               )
          changedTarget <- connected.text("[data-target]")
          changedUsed   <- connected.text("[data-used]")
          _ <- connected.submitForm("[data-profile-form]", Vector(NamePath.name -> "Ada"))
          submitted <- connected.text("[data-submitted]")
          submitUsed <- connected.text("[data-used]")
        yield assertTrue(
          changedTarget == NamePath.name,
          changedUsed == "true",
          submitted == "true",
          submitUsed == "true"
        )
      }
    },
    test("typed form changes keep rendered values and show errors only for used fields") {
      import SignupForm.*
      ZIO.scoped {
        for
          view        <- ConnectedRender.join(SignupView())
          _           <- view.changeForm(Definition)(Email.initial("ada"))
          emailOnly   <- view.text("[data-email-errors]")
          nameHidden  <- view.text("[data-name-errors]")
          _           <- view.changeForm(Definition)(Name.initial("Ada"))
          keptEmail   <- view.html.map(html =>
                           Jsoup.parseBodyFragment(html).select("input[name='signup[email]']").attr("value")
                         )
          _           <- view.submitForm(Definition)(Name.initial(""), Newsletter.initial(true))
          nameShown   <- view.text("[data-name-errors]")
          _           <- view.submitForm(Definition)(
                           Name.initial("Ada"),
                           Email.initial("ada@example.com")
                         )
          saved       <- view.text("[data-saved]")
        yield assertTrue(
          emailOnly == "Enter an email.",
          nameHidden.isEmpty,
          keptEmail == "ada",
          nameShown == "Enter your name.",
          saved == "Signup(Ada,ada@example.com,true)"
        )
      }
    },
    test("typed form actions require exactly one form for the definition") {
      val view = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)  = ZIO.unit
        def view(model: Signal[Unit]) = div("no form")
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          result    <- connected.submitForm(SignupForm.Definition)().either
        yield assertTrue(
          result.left.exists(_.getMessage.contains("form rendering fields of the 'signup' form"))
        )
      }
    },
    test("typed form actions pick one of several forms for a definition by DomRef") {
      import SignupForm.*
      ZIO.scoped {
        for
          view      <- ConnectedRender.join(TwinSignupView())
          ambiguous <- view.submitForm(Definition)().either
          _         <- view.changeForm(Definition, Shipping)(Name.initial("Ada"))
          changed   <- view.text("[data-shipping]")
          untouched <- view.text("[data-billing]")
          _         <- view.submitForm(Definition, Billing)(Name.initial("Bob"))
          submitted <- view.text("[data-billing]")
          _         <- view.submitForm(Definition, Shipping, RawFormSubmitter("intent", "save"))(
                         Name.initial("Cy")
                       )
          withSubmitter <- view.text("[data-shipping]")
          missing       <- view.changeForm(Definition, DomRef("missing"))().either
          unrelated     <- view.submitForm(Definition, Notes)().either
        yield assertTrue(
          ambiguous.left.exists(_.getMessage.contains("found 2. Pass the DomRef of the form")),
          changed == "changed Ada",
          untouched.isEmpty,
          submitted == "submitted Bob",
          withSubmitter == "submitted Cy save",
          missing.left.exists(_.getMessage.contains("Expected one form with the ID 'missing'")),
          unrelated.left.exists(
            _.getMessage.contains("'notes' renders no field of the 'signup' form definition")
          )
        )
      }
    },
    test("clicks and reads elements by DomRef") {
      import SignupForm.*
      ZIO.scoped {
        for
          view   <- ConnectedRender.join(SignupView())
          _      <- view.click(Save)
          clicks <- view.text("[data-clicks]")
          label  <- view.text(Save)
        yield assertTrue(clicks == "1", label == "Save")
      }
    },
    test("joins a routed LiveView with its message type") {
      val search = scalive.live.queryOptional[String]("q")
      val view   = new LiveView.Routed[NavigationMsg, String, Option[String]]:
        def mount(params: Option[String], ctx: MountContext) = ZIO.succeed(params.getOrElse(""))
        def handleMessage(model: String, ctx: MessageContext) =
          case NavigationMsg.Patch => ZIO.succeed(model.toUpperCase)
        def view(model: Signal[String]) = span(dataAttr("query") := "", model)
      ZIO.scoped {
        for
          connected <- ConnectedRender.join(search, view, Some("typed"))
          before    <- connected.text("[data-query]")
          _         <- connected.send(NavigationMsg.Patch)
          after     <- connected.text("[data-query]")
          url       <- connected.currentUrl
        yield assertTrue(before == "typed", after == "TYPED", url.encode == "/?q=typed")
      }
    },
    test("dispatches definition-owned submit actions as ordinary form data") {
      import TypedSubmitForm.{Msg as TypedSubmitMsg, *}

      val view = new LiveView[TypedSubmitMsg, String]:
        def mount(ctx: MountContext) = ZIO.succeed("")
        def handleMessage(model: String, ctx: MessageContext) =
          case TypedSubmitMsg.Submitted(event, intent) =>
            val decoded = intent match
              case Right(value)                           => value.wireValue
              case Left(FormSubmitter.DecodeError.Missing(_)) => "missing"
              case Left(FormSubmitter.DecodeError.Unknown(_, _)) => "unknown"
              case Left(FormSubmitter.DecodeError.Duplicate(_, _)) => "duplicate"
            ZIO.succeed(
              s"$decoded:${event.rawSubmitter.isDefined}:${event.form.valueOption.map(_.name).getOrElse("")}"
            )
        override def view(model: Signal[String]) =
          form(
            dataAttr("typed-submit-form") := "",
            Definition.onSubmit(Submitter)(TypedSubmitMsg.Submitted.apply),
            input(nameAttr := Name.name),
            Submitter.button(Intent.Preview)("Preview"),
            Submitter.button(Intent.Save)("Save"),
            span(dataAttr("typed-submit-result") := "", model)
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.submitForm(
                 "[data-typed-submit-form]",
                 Vector(Name.name -> "Ada")
               )
          missing <- connected.text("[data-typed-submit-result]")
          _ <- connected.submitForm(
                 "[data-typed-submit-form]",
                 Vector(Name.name -> "Ada"),
                 submitter = Some(Submitter.raw(Intent.Preview))
               )
          preview <- connected.text("[data-typed-submit-result]")
        yield assertTrue(
          missing == "missing:false:Ada",
          preview == "preview:false:Ada"
        )
      }
    },
    test("reads event targets from phx-target only, like the Phoenix client") {
      val html =
        """<div id="page">
          |  <div id="child" data-phx-component="1">
          |    <button id="untargeted" phx-click="b1:1"></button>
          |    <button id="by-cid" phx-target="2" phx-click="b1:2"></button>
          |    <button id="to-self" phx-target="#child" phx-click="b1:3"></button>
          |    <button id="to-sibling" phx-target="#sibling-part" phx-click="b1:4"></button>
          |    <button id="to-page" phx-target="#page" phx-click="b1:5"></button>
          |    <button id="to-nothing" phx-target="#absent" phx-click="b1:6"></button>
          |    <button id="to-several" phx-target="button" phx-click="b1:7"></button>
          |  </div>
          |  <div data-phx-component="2"><span id="sibling-part"></span></div>
          |</div>""".stripMargin

      def target(id: String) =
        ZIO.attempt(ConnectedDom.eventTarget(ConnectedDom.selectOne(html, s"#$id")))

      for
        untargeted <- target("untargeted")
        byCid      <- target("by-cid")
        toSelf     <- target("to-self")
        toSibling  <- target("to-sibling")
        toPage     <- target("to-page")
        toNothing  <- target("to-nothing").either
        toSeveral  <- target("to-several").either
      yield assertTrue(
        untargeted.isEmpty,
        byCid.contains(2L),
        toSelf.contains(1L),
        toSibling.contains(2L),
        toPage.isEmpty,
        toNothing.isLeft,
        toSeveral.isLeft
      )
    },
    test("collects form controls by form owner in tree order, like FormData") {
      val html =
        """<form id="first">
          |  <input name="tag" value="first-own">
          |  <input name="tag" form="second" value="nested-elsewhere">
          |  <input name="lost" form="missing" value="no-owner">
          |  <input name="lost" form="" value="empty-owner">
          |</form>
          |<input name="tag" form="first" value="outside-in">
          |<form id="second"><input name="tag" value="second-own"></form>
          |<div id="second-copy"><input name="tag" form="second-copy" value="not-a-form"></div>
          |""".stripMargin

      def fields(id: String) =
        ZIO.attempt(ConnectedDom.controls(ConnectedDom.selectOne(html, s"#$id")).successful)

      for
        first  <- fields("first")
        second <- fields("second")
      yield assertTrue(
        first == Vector("tag" -> "first-own", "tag" -> "outside-in"),
        second == Vector("tag" -> "nested-elsewhere", "tag" -> "second-own")
      )
    },
    test("finds the form for a definition by the controls it owns") {
      val html =
        """<form id="decoy"><input name="signup[email]" form="signup" value="ada@example.com"></form>
          |<form id="signup" phx-submit="save"></form>
          |<input name="signup[name]" form="signup" value="Ada">
          |""".stripMargin

      for
        form   <- ZIO.attempt(ConnectedDom.formFor(html, SignupForm.Definition))
        fields <- ZIO.attempt(ConnectedDom.controls(form).successful)
      yield assertTrue(
        form.id == "signup",
        fields == Vector("signup[email]" -> "ada@example.com", "signup[name]" -> "Ada")
      )
    },
    test("skips controls disabled by any fieldset ancestor, except in its first legend") {
      val html =
        """<fieldset disabled>
          |  <form id="inside"><input name="inside" value="disabled"></form>
          |  <input name="outside" form="legend" value="disabled">
          |</fieldset>
          |<form id="legend">
          |  <fieldset disabled>
          |    <legend>
          |      <input name="first-legend" value="enabled">
          |      <fieldset disabled><input name="inner-fieldset" value="disabled"></fieldset>
          |    </legend>
          |    <legend><input name="second-legend" value="disabled"></legend>
          |    <div><legend><input name="nested-legend" value="disabled"></legend></div>
          |    <input name="body" value="disabled">
          |  </fieldset>
          |</form>
          |""".stripMargin

      def fields(id: String) =
        ZIO.attempt(ConnectedDom.controls(ConnectedDom.selectOne(html, s"#$id")))

      for
        inside <- fields("inside")
        legend <- fields("legend")
      yield assertTrue(
        inside.successful.isEmpty,
        inside.names == Set("inside"),
        legend.successful == Vector("first-legend" -> "enabled")
      )
    },
    test("sends a component's bindings to the instance that renders them") {
      object Counter extends LiveComponent[String, ChildMsg, Int]:
        def mount(props: String, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: String, model: Int, ctx: MessageContext) =
          case ChildMsg.Clicked => ZIO.succeed(model + 1)
        override def view(props: Signal[String], model: Signal[Int]) =
          div(
            span(idAttr := props.map(_ + "-count"), model.map(_.toString)),
            button(idAttr := props.map(_ + "-click"), on.click(ChildMsg.Clicked))
          )

      val parent = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) =
          div(
            component(Counter, "first").render("first"),
            component(Counter, "second").render("second")
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(parent)
          html      <- connected.html
          _         <- connected.click("#first-click")
          first     <- connected.text("#first-count")
          second    <- connected.text("#second-count")
          button = ConnectedDom.selectOne(html, "#first-click")
        yield assertTrue(
          first == "1",
          second == "0",
          button.attr("phx-target") == button.parent().attr("data-phx-component")
        )
      }
    },
    test("follows an explicit selector target inside a component, like the Phoenix client") {
      object Panel extends LiveComponent[Unit, ChildMsg, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          case ChildMsg.Clicked => ZIO.succeed(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int]) =
          div(
            idAttr := "panel",
            span(idAttr := "panel-count", model.map(_.toString)),
            button(
              idAttr := "to-panel",
              phx.target(DomSelector.css("#panel")),
              on.click(ChildMsg.Clicked)
            ),
            button(
              idAttr := "to-page",
              phx.target(DomSelector.css("#page")),
              on.click(ChildMsg.Clicked)
            )
          )

      val parent = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) =
          div(idAttr := "page", component(Panel, "panel").render(()))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(parent)
          _         <- connected.click("#to-panel")
          count     <- connected.text("#panel-count")
          toPage    <- connected.click("#to-page").either
          joined    <- connected.isJoined
        yield assertTrue(count == "1", toPage.isLeft, joined)
      }
    },
    test("delivers a component binding addressed to another component to that component") {
      object Sibling extends LiveComponent[Unit, SiblingMsg, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          case SiblingMsg.Ping => ZIO.succeed(model + 1)
        override def view(
          props: Signal[Unit],
          model: Signal[Int],
        ) =
          span(dataAttr("sibling-count") := "", model.map(_.toString))

      val sibling = component(Sibling, "sibling")
      val missing = component(Sibling, "missing")

      object Child extends LiveComponent[Unit, ChildMsg, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          case ChildMsg.Clicked => ZIO.succeed(model + 1)
        override def view(props: Signal[Unit], model: Signal[Int]) =
          div(
            span(dataAttr("child-count") := "", model.map(_.toString)),
            button(dataAttr("click-child") := "", on.click(ChildMsg.Clicked)),
            button(
              dataAttr("ping-sibling") := "",
              on.click.to(sibling)(SiblingMsg.Ping)
            ),
            button(
              dataAttr("ping-missing") := "",
              on.click.to(missing)(SiblingMsg.Ping)
            )
          )

      val parent = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) =
          div(component(Child, "child").render(()), sibling.render(()))

      ZIO.scoped {
        for
          connected    <- ConnectedRender.join(parent)
          _            <- connected.click("[data-click-child]")
          _            <- connected.click("[data-ping-sibling]")
          childCount   <- connected.text("[data-child-count]")
          siblingCount <- connected.text("[data-sibling-count]")
          missingPing  <- connected.click("[data-ping-missing]").either
          joined       <- connected.isJoined
        yield assertTrue(childCount == "1", siblingCount == "1", missingPing.isLeft, joined)
      }
    },
    test("handles keyed and full client flash clearing for root and component targets") {
      val info    = FlashKind("info")
      val error   = FlashKind("error")
      val warning = FlashKind("warning")

      ZIO.scoped {
        for
          rootRawCalls      <- Ref.make(0)
          componentRawCalls <- Ref.make(0)
          component = new LiveComponent.Eventless[Unit, Unit]:
                        override val hooks =
                          ComponentLiveHooks.empty[Unit, Nothing, Unit].onRawEvent {
                            (_, model, _, _) =>
                              componentRawCalls
                                .update(_ + 1).as(LiveEventHookResult.cont(model))
                          }
                        def mount(props: Unit, ctx: MountContext) = ZIO.unit
                        override def view(
                          props: Signal[Unit],
                          model: Signal[Unit],
                        ) =
                          div(
                            flash(error)(message =>
                              button(
                                dataAttr("clear-all-flash") := "",
                                flash.clearOnClick,
                                message
                              )
                            ),
                            flash(warning)(message =>
                              span(dataAttr("warning-flash") := "", message)
                            )
                          )
          instance = scalive.component(component, "flash")
          view = new LiveView.Eventless[Unit]:
                   override val hooks = LiveHooks.empty[Nothing, Unit].onRawEvent {
                     (model, _, _) =>
                       rootRawCalls.update(_ + 1).as(LiveEventHookResult.cont(model))
                   }
                   def mount(ctx: MountContext) =
                     ctx.flash.put(info, "Info") *>
                       ctx.flash.put(error, "Error") *>
                       ctx.flash.put(warning, "Warning")
                   override def view(model: Signal[Unit]) =
                     div(
                       flash(info)(message =>
                         button(
                           dataAttr("clear-info-flash") := "",
                           flash.clearOnClick(info),
                           message
                         )
                       ),
                       instance.render(())
                     )
          connected <- ConnectedRender.join(view)
          initialInfo <- connected.text("[data-clear-info-flash]")
          initialError <- connected.text("[data-clear-all-flash]")
          initialWarning <- connected.text("[data-warning-flash]")
          _          <- connected.click("[data-clear-info-flash]")
          afterKeyed <- connected.html
          _          <- connected.click("[data-clear-all-flash]")
          afterAll   <- connected.html
          rootCalls  <- rootRawCalls.get
          componentCalls <- componentRawCalls.get
          joined     <- connected.isJoined
        yield assertTrue(
          initialInfo == "Info",
          initialError == "Error",
          initialWarning == "Warning",
          Jsoup.parseBodyFragment(afterKeyed).select("[data-clear-info-flash]").isEmpty,
          !Jsoup.parseBodyFragment(afterKeyed).select("[data-clear-all-flash]").isEmpty,
          !Jsoup.parseBodyFragment(afterKeyed).select("[data-warning-flash]").isEmpty,
          Jsoup.parseBodyFragment(afterAll).select("[data-clear-all-flash]").isEmpty,
          Jsoup.parseBodyFragment(afterAll).select("[data-warning-flash]").isEmpty,
          rootCalls == 0,
          componentCalls == 0,
          joined
        )
      }
    },
    test("an upload renders its entries from the runtime as they upload") {
      val definition = LiveUploadDef.inMemory("notes", LiveUploadAccept.only(".txt"))
      val view = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ctx.uploads.allow(definition)
        override def view(model: Signal[Unit]) =
          div(
            definition.render(upload => liveFileInput(upload)),
            definition.render(upload =>
              upload.map(_.entries).splitBy(_.ref)((_, entry) =>
                span(cls := "entry", entry.map(e => s"${e.client.fileName}:${e.progress}"))
              )
            )
          )

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.upload(
                 definition,
                 "a.txt",
                 "text/plain",
                 Chunk.fromArray("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
               )
          html <- connected.awaitHtml("entry rendered", 3.seconds)(_.contains("a.txt:100"))
        yield assertTrue(html.contains("a.txt:100"))
      }
    },
    test("streams hosted uploads using the preflight chunk size") {
      val definition = LiveUploadDef.inMemory(
        "small-chunks",
        LiveUploadAccept.only(".txt"),
        maxFileSize = 32L,
        chunkSize = 3
      )
      val view = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ctx.uploads.allow(definition)
        override def view(model: Signal[Unit]) = div(definition.render(liveFileInput(_)))

      ZIO.scoped {
        for
          connected <- ConnectedRender.join(view)
          _ <- connected.upload(
                 definition,
                 "small.txt",
                 "text/plain",
                 Chunk.fromArray("seven77".getBytes(java.nio.charset.StandardCharsets.UTF_8))
               )
          joined <- connected.isJoined
        yield assertTrue(joined)
      }
    },
    test("joins nested views and releases their resources when the parent leaves") {
      for
        acquired <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        result <- ZIO.scoped {
                    val child = new LiveView[ResourceMsg, Unit]:
                      def mount(ctx: MountContext) =
                        ctx.connection match
                          case Connection.Disconnected => ZIO.unit
                          case Connection.Connected(connected) =>
                            connected.resources
                              .acquireRelease(acquired.succeed(()).unit)(_ =>
                                released.succeed(()).unit
                              ).unit
                      def handleMessage(model: Unit, ctx: MessageContext) =
                        case ResourceMsg.Tick => ZIO.succeed(model)
                      override def view(model: Signal[Unit]) = div("resource child")

                    val parent = new LiveView.Eventless[Unit]:
                      def mount(ctx: MountContext) = ZIO.unit
                      override def view(model: Signal[Unit]) =
                        div(liveView("resource-child", child))

                    for
                      connected <- ConnectedRender.join(parent)
                      nested    <- connected.joinNested("resource-child")
                      joined    <- nested.isJoined
                      _ <- acquired.await.timeoutFail(
                             new RuntimeException("Nested resource did not start")
                           )(3.seconds)
                      _         <- connected.leave
                      _ <- released.await.timeoutFail(
                             new RuntimeException("Nested resource was not released")
                           )(3.seconds)
                      removed <- nested.isJoined
                    yield assertTrue(joined, !removed)
                  }
      yield result
    },
    test("disconnect guards from either joined view retire the shared physical session") {
      ZIO.scoped {
        ZIO.foreach(Vector(false, true))(triggerNested =>
          terminalGuardCleanup(LiveConnectedTurnFailure.disconnect("test"), triggerNested)
        ).map(results => assertTrue(results.forall(identity)))
      }
    },
    test("reload guards retire the shared physical session") {
      ZIO.scoped {
        terminalGuardCleanup(LiveConnectedTurnFailure.reload("test"), triggerNested = true)
          .map(assertTrue(_))
      }
    },
    test("same-session patch navigation keeps root and nested views joined") {
      val child = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div("patch child")

      val parent = new LiveView[NavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case NavigationMsg.Patch => ctx.nav.pushPatchUnsafe("?patched=true").as(model)
        override def view(model: Signal[Unit]) =
          div(
            button(dataAttr("patch") := "", on.click(NavigationMsg.Patch), "Patch"),
            liveView("patch-child", child)
          )

      val application = scalive.Live.router(
        scalive.live.guardConnectedTurns(_ => ZIO.unit) -> parent
      )

      ZIO.scoped {
        for
          root   <- ConnectedRender.join(application, config, Request.get(URL.root))
          nested <- root.joinNested("patch-child")
          _      <- root.click("[data-patch]")
          rootJoined   <- root.isJoined
          nestedJoined <- nested.isJoined
        yield assertTrue(rootJoined, nestedJoined)
      }
    },
    test("routed LiveViews patch by their own parameters") {
      val search = scalive.live.queryOptional[String]("q")
      val view   = new LiveView.Routed[NavigationMsg, Option[String], Option[String]]:
        def mount(params: Option[String], ctx: MountContext) = ZIO.succeed(params)
        def handleMessage(model: Option[String], ctx: MessageContext) =
          case NavigationMsg.Patch => ctx.nav.pushPatch(Some("typed")).as(model)
        override def handleParams(
          model: Option[String],
          params: Option[String],
          url: URL,
          ctx: ParamsContext
        ) = ZIO.succeed(params)
        def view(model: Signal[Option[String]]) =
          div(
            button(dataAttr("patch") := "", on.click(NavigationMsg.Patch), "Patch"),
            span(dataAttr("query") := "", model.map(_.getOrElse("none")))
          )

      ZIO.scoped {
        for
          root    <- ConnectedRender.join(scalive.Live.router(search -> view), config, Request.get(URL.root))
          before  <- root.text("[data-query]")
          _       <- root.click("[data-patch]")
          after   <- (ZIO.yieldNow *> root.text("[data-query]"))
                       .repeatUntil(_ == "typed")
                       .timeout(3.seconds)
          joined  <- root.isJoined
        yield assertTrue(before == "none", after.contains("typed"), joined)
      }
    },
    test("a patch to another route's location stops the LiveView") {
      val otherRoute = scalive.live / "other"
      val other      = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)           = ZIO.unit
        override def view(model: Signal[Unit]) = div("other")

      val source = new LiveView[NavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case NavigationMsg.Patch => ctx.nav.pushPatch(otherRoute.location).as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr("patch") := "", on.click(NavigationMsg.Patch), "Patch")

      val application = scalive.Live.router(scalive.live -> source, otherRoute -> other)

      ZIO.scoped {
        for
          root    <- ConnectedRender.join(application, config, Request.get(URL.root))
          _       <- root.click("[data-patch]")
          stopped <- (ZIO.yieldNow *> root.isJoined)
                       .repeatUntil(joined => !joined)
                       .timeout(3.seconds)
        yield assertTrue(stopped.isDefined)
      }
    },
    test("a patch whose URL resolves to an earlier route stops the LiveView") {
      val newItem = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)           = ZIO.unit
        override def view(model: Signal[Unit]) = div("new item")

      val item = new LiveView.Routed[NavigationMsg, String, String]:
        def mount(params: String, ctx: MountContext) = ZIO.succeed(params)
        def handleMessage(model: String, ctx: MessageContext) =
          case NavigationMsg.Patch => ctx.nav.pushPatch("new").as(model)
        override def handleParams(model: String, params: String, url: URL, ctx: ParamsContext) =
          ZIO.succeed(params)
        def view(model: Signal[String]) =
          button(dataAttr("patch") := "", on.click(NavigationMsg.Patch), model)

      val application = scalive.Live.router(
        scalive.live / "items" / "new"                     -> newItem,
        scalive.live / "items" / PathCodec.string("id") -> item
      )

      ZIO.scoped {
        for
          root    <- ConnectedRender.join(application, config, Request.get(url("/items/1")))
          _       <- root.click("[data-patch]")
          stopped <- (ZIO.yieldNow *> root.isJoined)
                       .repeatUntil(joined => !joined)
                       .timeout(3.seconds)
        yield assertTrue(stopped.isDefined)
      }
    },
    test("routed joins execute physical session admission") {
      val sessionId = TestSessionId("admitted")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state       = TestAuthState(active, revalidations)
        application = admittedApplication()
        result <- ZIO
                    .scoped {
                      for
                        connected <- ConnectedRender.join(
                                       application,
                                       config,
                                       Request.get(url("/?session=admitted"))
                                     )
                        name   <- connected.text("[data-user-name]")
                        checks <- revalidations.get
                      yield assertTrue(name == "admitted", checks == 1)
                    }.provide(
                      ZLayer.succeed[TestAuthState](state),
                      ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                    )
      yield result
    },
    test("route resources survive messages and patches but restart for reconnect and navigation") {
      val sessionId                      = TestSessionId("resources")
      def resourceNames(context: String) = Vector(
        "session:resources",
        s"route:resources:$context",
        s"mount:resources:$context"
      )
      val initialResources     = resourceNames("initial:/")
      val reconnectedResources = resourceNames("reconnected:/")
      val destinationResources = resourceNames("destination:/next")

      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        events        <- Ref.make(Vector.empty[String])
        context       <- Ref.make("initial")
        state       = TestAuthState(active, revalidations)
        application = admittedApplication(event => events.update(_ :+ event), context.get)
        result <- ZIO
                    .scoped {
                      for
                        client <- ConnectedRender.open(
                                    application,
                                    config,
                                    Request.get(url("/?session=resources"))
                                  )
                        prepared           <- events.get
                        first              <- client.join
                        joined             <- events.get
                        initial            <- first.text("[data-resource-context]")
                        nested             <- first.joinNested("admission-child")
                        nestedJoined       <- nested.isJoined
                        afterNested        <- events.get
                        _                  <- first.click("[data-admission-increment]")
                        afterMessage       <- events.get
                        _                  <- first.click("[data-admission-patch]")
                        _                  <- first.click("[data-admission-increment]")
                        count              <- first.text("[data-admission-count]")
                        afterPatch         <- events.get
                        patchedChildJoined <- nested.isJoined
                        _                  <- client.disconnect
                        disconnected       <- awaitResourceReleases(events, 3)
                        firstJoined        <- first.isJoined
                        childJoined        <- nested.isJoined
                        _                  <- context.set("reconnected")
                        second             <- client.reconnect
                        reconnected        <- second.text("[data-resource-context]")
                        afterReconnect     <- events.get
                        _                  <- context.set("destination")
                        action             <- second.click("[data-admission-navigate]")
                        third              <- action match
                                   case ConnectedAction.LiveNavigation(navigation) =>
                                     navigation.follow
                                   case other =>
                                     ZIO.fail(Exception(s"Expected live navigation, got $other."))
                        navigated    <- awaitResourceReleases(events, 6)
                        destination  <- third.text("[data-resource-context]")
                        sourceJoined <- second.isJoined
                        nextJoined   <- third.isJoined
                        _            <- client.disconnect
                        finished     <- awaitResourceReleases(events, 9)
                      yield assertTrue(
                        prepared.isEmpty,
                        joined == initialResources.map("acquire:" + _),
                        initial == "initial:/",
                        nestedJoined,
                        afterNested == (joined :+ "mount:nested"),
                        afterMessage == afterNested,
                        count == "2",
                        afterPatch == afterNested,
                        patchedChildJoined,
                        disconnected.filter(_.startsWith("release:")) ==
                          initialResources.reverse.map("release:" + _),
                        !firstJoined,
                        !childJoined,
                        reconnected == "reconnected:/",
                        afterReconnect.filter(_.startsWith("acquire:")) ==
                          (initialResources ++ reconnectedResources).map("acquire:" + _),
                        afterReconnect.filter(_.startsWith("release:")) ==
                          initialResources.reverse.map("release:" + _),
                        destination == "destination:/next",
                        !sourceJoined,
                        nextJoined,
                        navigated.filter(_.startsWith("release:")) ==
                          (initialResources.reverse ++ reconnectedResources.reverse)
                            .map("release:" + _),
                        finished.filter(_.startsWith("acquire:")) ==
                          (initialResources ++ reconnectedResources ++ destinationResources)
                            .map("acquire:" + _),
                        finished.filter(_.startsWith("release:")) ==
                          (initialResources.reverse ++ reconnectedResources.reverse ++
                            destinationResources.reverse).map("release:" + _)
                      )
                    }.provide(
                      ZLayer.succeed[TestAuthState](state),
                      ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                    )
      yield result
      end for
    },
    test("invalidation retires the transport and rejected reconnect acquires no resources") {
      val sessionId = TestSessionId("revoked")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        events        <- Ref.make(Vector.empty[String])
        state = TestAuthState(active, revalidations)
        result <- ZIO
                    .scoped {
                      for
                        client <- ConnectedRender.open(
                                    admittedApplication(event => events.update(_ :+ event)),
                                    config,
                                    Request.get(url("/?session=revoked"))
                                  )
                        connected  <- client.join
                        acquired   <- events.get
                        _          <- active.set(Set.empty)
                        _          <- connections.disconnect(sessionId)
                        _          <- connected.awaitDisconnected
                        _          <- awaitResourceReleases(events, 3)
                        retry      <- client.reconnect.either
                        afterRetry <- events.get
                        checks     <- revalidations.get
                      yield assertTrue(
                        retry == Left(ConnectedJoinFailure.Unauthorized),
                        acquired.size == 3,
                        afterRetry.filter(_.startsWith("acquire:")) == acquired,
                        checks == 2
                      )
                    }.provide(
                      ZLayer.succeed[TestAuthState](state),
                      ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                    )
      yield result
      end for
    },
    test("reconnect and navigation progress harness-owned mount parameters") {
      def connectedParams(ctx: scalive.MountContext[?, ?]): Task[(Long, String)] =
        ctx.connection match
        case Connection.Disconnected => ZIO.succeed(-1L -> "disconnected")
        case Connection.Connected(connected) =>
          ZIO.attempt {
            val mounts = connected.connectParams.get("_mounts") match
              case Some(Json.Num(value)) => value.longValueExact()
              case value                 => throw Exception(s"Invalid _mounts value: $value")
            val custom = connected.connectParams.get("custom").flatMap(_.asString).getOrElse {
              throw Exception("Missing custom connect parameter")
            }
            mounts -> custom
          }

      def content(model: Signal[(Long, String)]) =
        div(
          span(dataAttr("mounts") := "", model.map(_._1.toString)),
          span(dataAttr("custom") := "", model.map(_._2))
        )

      val source = new LiveView[RoutedNavigationMsg, (Long, String)]:
        def mount(ctx: MountContext) = connectedParams(ctx)
        def handleMessage(model: (Long, String), ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe("/next").as(model)
        override def view(model: Signal[(Long, String)]) =
          div(
            content(model),
            button(dataAttr("navigate") := "", on.click(RoutedNavigationMsg.Navigate), "Next")
          )

      val destination = new LiveView.Eventless[(Long, String)]:
        def mount(ctx: MountContext) = connectedParams(ctx)
        override def view(model: Signal[(Long, String)]) = content(model)

      val application = scalive.Live.router(
        scalive.live -> source,
        (scalive.live / "next") -> destination
      )

      ZIO.scoped {
        for
          client <- ConnectedRender.open(
                      application,
                      config,
                      Request.get(URL.root),
                      connectParams = Map(
                        "_mounts" -> Json.Num(99),
                        "custom"  -> Json.Str("retained")
                      )
                    )
          first      <- client.join
          firstMount <- first.text("[data-mounts]")
          second     <- client.reconnect
          secondMount <- second.text("[data-mounts]")
          firstJoined <- first.isJoined
          action      <- second.click("[data-navigate]")
          third <- action match
                     case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                     case other => ZIO.fail(Exception(s"Expected live navigation, got $other."))
          thirdMount  <- third.text("[data-mounts]")
          customParam <- third.text("[data-custom]")
        yield assertTrue(
          firstMount == "0",
          secondMount == "1",
          !firstJoined,
          thirdMount == "2",
          customParam == "retained"
        )
      }
    },
    test("disconnect waits for an in-flight join and closes its transport") {
      for
        connectedMount <- Promise.make[Nothing, Unit]
        releaseMount   <- Promise.make[Nothing, Unit]
        view = new LiveView.Eventless[Unit]:
                 def mount(ctx: MountContext) = ctx.connection match
                   case Connection.Disconnected => ZIO.unit
                   case Connection.Connected(_) =>
                     connectedMount.succeed(()).unit *> releaseMount.await
                 override def view(model: Signal[Unit]) = div("connected")
        result <- ZIO.scoped {
                    for
                      client <- ConnectedRender.open(
                                  scalive.Live.router(scalive.live -> view),
                                  config,
                                  Request.get(URL.root)
                                )
                      joining         <- client.join.fork
                      _               <- connectedMount.await
                      disconnecting   <- client.disconnect.fork
                      _               <- releaseMount.succeed(())
                      connected       <- joining.join
                      _               <- disconnecting.join
                      _               <- connected.awaitDisconnected
                      joinedAfterClose <- connected.isJoined
                    yield assertTrue(!joinedAfterClose)
                  }
      yield result
    },
    test("live navigation revalidates session admission and rejection acquires no resources") {
      val sessionId = TestSessionId("navigation")
      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        events        <- Ref.make(Vector.empty[String])
        state = TestAuthState(active, revalidations)
        result <- ZIO
                    .scoped {
                      for
                        client <- ConnectedRender.open(
                                    admittedApplication(event => events.update(_ :+ event)),
                                    config,
                                    Request.get(url("/?session=navigation"))
                                  )
                        connected <- client.join
                        acquired  <- events.get
                        outcome   <- connected.click("[data-admission-navigate]")
                        _         <- active.set(Set.empty)
                        rejected  <- outcome match
                                      case ConnectedAction.LiveNavigation(navigation) =>
                                        navigation.follow.either
                                      case other =>
                                        ZIO.fail(
                                          Exception(s"Expected live navigation, got $other.")
                                        )
                        _       <- connected.awaitDisconnected
                        retired <- awaitResourceReleases(events, 3)
                        checks  <- revalidations.get
                      yield assertTrue(
                        rejected == Left(ConnectedJoinFailure.Disconnected),
                        acquired.size == 3,
                        retired.filter(_.startsWith("acquire:")) == acquired,
                        checks == 2
                      )
                    }.provide(
                      ZLayer.succeed[TestAuthState](state),
                      ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                    )
      yield result
      end for
    },
    test("live navigation rejoins the destination through redirect admission") {
      val source = new LiveView[RoutedNavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe("/next").as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr("navigate") := "", on.click(RoutedNavigationMsg.Navigate), "Next")

      val destination = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        override def view(model: Signal[Unit]) = div(dataAttr("destination") := "", "destination")

      val application = scalive.Live.router(
        scalive.live -> source,
        (scalive.live / "next") -> destination
      )

      ZIO.scoped {
        for
          root    <- ConnectedRender.join(application, config, Request.get(URL.root))
          outcome <- root.click("[data-navigate]")
          next <- outcome match
                    case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                    case other => ZIO.fail(Exception(s"Expected live navigation, got $other."))
          rendered <- next.text("[data-destination]")
          sourceJoined <- root.isJoined
          nextJoined   <- next.isJoined
        yield assertTrue(rendered == "destination", !sourceJoined, nextJoined)
      }
    },
    test("live navigation derives fresh destination route context after session admission") {
      val routeMounts = AtomicInteger()
      val source = new LiveView[RoutedNavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe("/next").as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr("navigate") := "", on.click(RoutedNavigationMsg.Navigate), "Next")

      val routeAuthorization = LiveRouteMountAspect.make[Any, Unit, TestUser, String] {
        (request, user) =>
          ZIO.succeed {
            routeMounts.incrementAndGet()
            s"${user.name}:${request.url.path.encode}"
          }
      }
      val destination = (scalive.live / "next")
        .withMountAspect(routeAuthorization)
        .context((context: (TestUser, String)) => new LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed(context._2)
          override def view(model: Signal[String]) =
            div(dataAttr("authorized-destination") := "", model)
        )
      val application = scalive.Live.router(
        scalive.Live
          .session("authorized-navigation")
          .withAdmission(authentication)(_.sessionId)(
            scalive.live.context((_: TestUser) => source),
            destination
          )
      )
      val sessionId = TestSessionId("navigation")

      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state           = TestAuthState(active, revalidations)
        result <- ZIO.scoped {
                    for
                      root <- ConnectedRender.join(
                                application,
                                config,
                                Request.get(url("/?session=navigation"))
                              )
                      action <- root.click("[data-navigate]")
                      next <- action match
                                case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                                case other =>
                                  ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      rendered <- next.text("[data-authorized-destination]")
                      checks   <- revalidations.get
                    yield assertTrue(
                      rendered == "navigation:/next",
                      routeMounts.get() == 1,
                      checks == 2
                    )
                  }.provide(
                    ZLayer.succeed[TestAuthState](state),
                    ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                  )
      yield result
    },
    test("destination route denial happens before lifecycle construction or resource acquisition") {
      val factories                                                = AtomicInteger()
      val resourceStarts                                           = AtomicInteger()
      def startResource(resources: ConnectedResources): Task[Unit] =
        resources.acquireRelease(ZIO.succeed(resourceStarts.incrementAndGet()))(_ => ZIO.unit).unit

      val source = new LiveView[RoutedNavigationMsg, Unit]:
        def mount(ctx: MountContext)                        = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe("/denied").as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr("navigate") := "", on.click(RoutedNavigationMsg.Navigate), "Denied")

      val denied = LiveRouteMountAspect.make[Any, Unit, TestUser, Unit]((_, _) =>
        ZIO.fail(LiveRouteMountFailure.forbidden("destination access revoked"))
      )
      val destination = (scalive.live / "denied")
        .withMountAspect(denied)
        .withConnectedResources((_, resources) => startResource(resources)) -> {
          factories.incrementAndGet()
          new LiveView.Eventless[Unit]:
            def mount(ctx: MountContext)  = ZIO.unit
            def view(model: Signal[Unit]) = div("denied")
        }
      val application = scalive.Live.router(
        scalive.Live
          .session("denied-navigation")
          .withAdmission(authentication)(_.sessionId)
          .withConnectedResources((_, resources) => startResource(resources))(
            scalive.live.context((_: TestUser) => source),
            destination
          )
      )
      val sessionId = TestSessionId("navigation")

      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state = TestAuthState(active, revalidations)
        result <- ZIO
                    .scoped {
                      for
                        root <- ConnectedRender.join(
                                  application,
                                  config,
                                  Request.get(url("/?session=navigation"))
                                )
                        action <- root.click("[data-navigate]")
                        denied <- action match
                                    case ConnectedAction.LiveNavigation(navigation) =>
                                      navigation.follow.either
                                    case other =>
                                      ZIO.fail(Exception(s"Expected live navigation, got $other."))
                        checks <- revalidations.get
                      yield assertTrue(
                        denied == Left(ConnectedJoinFailure.Unauthorized),
                        factories.get() == 0,
                        resourceStarts.get() == 1,
                        checks == 2
                      )
                    }.provide(
                      ZLayer.succeed[TestAuthState](state),
                      ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                    )
      yield result
      end for
    },
    test("route denial after a successful navigation reports its join failure") {
      val factories = AtomicInteger()
      def navigating(to: String, marker: String) = new LiveView[RoutedNavigationMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case RoutedNavigationMsg.Navigate => ctx.nav.pushNavigateUnsafe(to).as(model)
        override def view(model: Signal[Unit]) =
          button(dataAttr(marker) := "", on.click(RoutedNavigationMsg.Navigate), "Navigate")

      val denied = LiveRouteMountAspect.make[Any, Unit, TestUser, Unit]((_, _) =>
        ZIO.fail(LiveRouteMountFailure.forbidden("destination access revoked"))
      )
      val deniedRoute = (scalive.live / "denied").withMountAspect(denied) -> {
        factories.incrementAndGet()
        new LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div("denied")
      }
      val application = scalive.Live.router(
        scalive.Live
          .session("success-then-denied")
          .withAdmission(authentication)(_.sessionId)(
            scalive.live.context((_: TestUser) => navigating("/middle", "to-middle")),
            (scalive.live / "middle")
              .context((_: TestUser) => navigating("/denied", "to-denied")),
            deniedRoute
          )
      )
      val sessionId = TestSessionId("navigation")

      for
        active        <- Ref.make(Set(sessionId))
        revalidations <- Ref.make(0)
        connections   <- LiveConnections.make[TestSessionId](_ => ZIO.unit)
        state           = TestAuthState(active, revalidations)
        result <- ZIO.scoped {
                    for
                      root <- ConnectedRender.join(
                                application,
                                config,
                                Request.get(url("/?session=navigation"))
                              )
                      firstAction <- root.click("[data-to-middle]")
                      middle <- firstAction match
                                  case ConnectedAction.LiveNavigation(navigation) =>
                                    navigation.follow
                                  case other =>
                                    ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      secondAction <- middle.click("[data-to-denied]")
                      denied <- secondAction match
                                  case ConnectedAction.LiveNavigation(navigation) =>
                                    navigation.follow.either
                                  case other =>
                                    ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      checks <- revalidations.get
                    yield assertTrue(
                      denied == Left(ConnectedJoinFailure.Unauthorized),
                      factories.get() == 0,
                      checks == 3
                    )
                  }.provide(
                    ZLayer.succeed[TestAuthState](state),
                    ZLayer.succeed[LiveConnections[TestSessionId]](connections)
                  )
      yield result
    },
    test("server-originated live navigation remains observable and followable") {
      for
        release <- Promise.make[Nothing, Unit]
        source = new LiveView[RoutedNavigationMsg, Unit]:
                   private val navigate = AsyncKey[Unit]("navigate-after-join")
                   def mount(ctx: MountContext) = ctx.connection match
                     case Connection.Disconnected => ZIO.unit
                     case Connection.Connected(connected) =>
                       connected.async
                         .start(navigate)(release.await)(_ => RoutedNavigationMsg.Navigate).unit
                   def handleMessage(model: Unit, ctx: MessageContext) =
                     case RoutedNavigationMsg.Navigate =>
                       ctx.nav.pushNavigateUnsafe("/next").as(model)
                   override def view(model: Signal[Unit]) = div("source")
        destination = new LiveView.Eventless[Unit]:
                        def mount(ctx: MountContext) = ZIO.unit
                        override def view(model: Signal[Unit]) =
                          div(dataAttr("async-destination") := "", "destination")
        application = scalive.Live.router(
                        scalive.live -> source,
                        (scalive.live / "next") -> destination
                      )
        result <- ZIO.scoped {
                    for
                      connected <- ConnectedRender.join(application, config, Request.get(URL.root))
                      _         <- release.succeed(())
                      outcome   <- connected.awaitAction
                      next <- outcome match
                                case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                                case other =>
                                  ZIO.fail(Exception(s"Expected live navigation, got $other."))
                      rendered <- next.text("[data-async-destination]")
                    yield assertTrue(rendered == "destination")
                  }
      yield result
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)

  private val authentication =
    LiveSessionMountAspect.fromRequest[TestAuthState, TestClaims, TestUser](
      request =>
        for
          state <- ZIO.service[TestAuthState]
          id     = TestSessionId(request.url.queryParam("session").getOrElse("missing"))
          valid <- state.active.get.map(_.contains(id))
          user  <- if valid then ZIO.succeed(TestUser(id.value))
                   else ZIO.fail(zio.http.Response.unauthorized)
        yield TestClaims(id) -> user,
      (claims, _) =>
        for
          state <- ZIO.service[TestAuthState]
          _     <- state.revalidations.update(_ + 1)
          valid <- state.active.get.map(_.contains(claims.sessionId))
          user  <- if valid then ZIO.succeed(TestUser(claims.sessionId.value))
                   else ZIO.fail(LiveSessionMountFailure.unauthorized("revoked test session"))
        yield user
    )

  private def admittedApplication(
    record: String => UIO[Unit] = _ => ZIO.unit,
    routeContext: UIO[String] = ZIO.succeed("initial")
  ): LiveApplication[TestAuthState & LiveConnections[TestSessionId]] =
    def track(name: String, resources: ConnectedResources): Task[Unit] =
      resources.acquireRelease(record(s"acquire:$name"))(_ => record(s"release:$name")).unit

    def mountResources(name: String, ctx: scalive.MountContext[?, ?]): Task[Unit] =
      ctx.connection match
        case Connection.Disconnected         => ZIO.unit
        case Connection.Connected(connected) => track(s"mount:$name", connected.resources)

    val routeAuthorization = LiveRouteMountAspect.make[Any, Unit, TestUser, String] { (request, _) =>
      routeContext.map(value => s"$value:${request.url.path.encode}")
    }
    val child = new LiveView.Eventless[Unit]:
      def mount(ctx: MountContext) = ctx.connection match
        case Connection.Disconnected => ZIO.unit
        case Connection.Connected(_) => record("mount:nested")
      override def view(model: Signal[Unit]) = div("admission child")

    scalive.Live.router(
      scalive.Live
        .session("authenticated")
        .withAdmission(authentication)(_.sessionId)
        .withConnectedResources((user, resources) => track(s"session:${user.name}", resources))(
          scalive.live
            .withMountAspect(routeAuthorization)
            .withConnectedResources((context, resources) =>
              track(s"route:${context._1.name}:${context._2}", resources)
            )
            .context((context: (TestUser, String)) =>
              new LiveView[AdmissionMsg, Int]:
                def mount(ctx: MountContext) =
                  mountResources(s"${context._1.name}:${context._2}", ctx).as(0)
                def handleMessage(model: Int, ctx: MessageContext) =
                  case AdmissionMsg.Increment => ZIO.succeed(model + 1)
                  case AdmissionMsg.Patch     => ctx.nav.pushPatchUnsafe("?patched=true").as(model)
                  case AdmissionMsg.Navigate  => ctx.nav.pushNavigateUnsafe("/next").as(model)
                override def view(model: Signal[Int]) =
                  div(
                    dataAttr("user") := "",
                    span(dataAttr("user-name")        := "", context._1.name),
                    span(dataAttr("resource-context") := "", context._2),
                    span(dataAttr("admission-count")  := "", model.map(_.toString)),
                    button(
                      dataAttr("admission-increment") := "",
                      on.click(AdmissionMsg.Increment),
                      "Increment"
                    ),
                    button(
                      dataAttr("admission-patch") := "",
                      on.click(AdmissionMsg.Patch),
                      "Patch"
                    ),
                    button(
                      dataAttr("admission-navigate") := "",
                      on.click(AdmissionMsg.Navigate),
                      "Next"
                    ),
                    liveView("admission-child", child)
                  )
            ),
          (scalive.live / "next")
            .withMountAspect(routeAuthorization)
            .withConnectedResources((context, resources) =>
              track(s"route:${context._1.name}:${context._2}", resources)
            )
            .context((context: (TestUser, String)) =>
              new LiveView.Eventless[Unit]:
                def mount(ctx: MountContext) =
                  mountResources(s"${context._1.name}:${context._2}", ctx)
                override def view(model: Signal[Unit]) = div(
                  dataAttr("user") := "",
                  context._1.name,
                  span(dataAttr("resource-context") := "", context._2)
                )
            )
        )
    )
  end admittedApplication

  private def awaitResourceReleases(events: Ref[Vector[String]], expected: Int)
    : Task[Vector[String]] =
    (ZIO.yieldNow *> events.get)
      .repeatUntil(_.count(_.startsWith("release:")) >= expected)
      .timeoutFail(Exception(s"Expected $expected connected resource releases."))(3.seconds)

  private def url(value: String): URL = URL.decode(value).toOption.get

  private def terminalGuardCleanup(
    failure: LiveConnectedTurnFailure,
    triggerNested: Boolean
  ): ZIO[Scope, Throwable, Boolean] =
    val child = new LiveView[TerminalMsg, Unit]:
      def mount(ctx: MountContext) = ZIO.unit
      def handleMessage(model: Unit, ctx: MessageContext) =
        case TerminalMsg.Trigger => ZIO.succeed(model)
      override def view(model: Signal[Unit]) =
        button(dataAttr("trigger-nested") := "", on.click(TerminalMsg.Trigger), "Trigger nested")

    val parent = new LiveView[TerminalMsg, Unit]:
      def mount(ctx: MountContext) = ZIO.unit
      def handleMessage(model: Unit, ctx: MessageContext) =
        case TerminalMsg.Trigger => ZIO.succeed(model)
      override def view(model: Signal[Unit]) =
        div(
          button(dataAttr("trigger-root") := "", on.click(TerminalMsg.Trigger), "Trigger root"),
          liveView("terminal-child", child)
        )

    val application = scalive.Live.router(
      scalive.live.guardConnectedTurns(_ => ZIO.fail(failure)) -> parent
    )

    for
      root   <- ConnectedRender.join(application, config, Request.get(URL.root))
      nested <- root.joinNested("terminal-child")
      _ <- if triggerNested then nested.click("[data-trigger-nested]")
           else root.click("[data-trigger-root]")
      _ <- awaitUnjoined(root, nested)
      rootJoined   <- root.isJoined
      nestedJoined <- nested.isJoined
    yield !rootJoined && !nestedJoined

  private def awaitUnjoined(root: ConnectedView[?], nested: ConnectedView[?]): Task[Unit] =
    root.isJoined.zip(nested.isJoined).flatMap {
      case (false, false) => ZIO.unit
      case _              => ZIO.sleep(10.millis) *> awaitUnjoined(root, nested)
    }.timeoutFail(Exception("ConnectedRender session did not retire."))(3.seconds)
end ConnectedRenderSpec
