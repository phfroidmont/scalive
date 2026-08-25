package scalive.docs.examples

import zio.test.*

import scalive.*
import scalive.docs.model.ExampleCatalog

object ExampleRegistrySpec extends ZIOSpecDefault:
  override def spec = suite("ExampleRegistrySpec")(
    test("keeps executable entries aligned with generated descriptors") {
      assertTrue(
        ExampleRegistry.validationErrors.isEmpty,
        ExampleRegistry.entries.map(_.descriptor) == ExampleCatalog.entries,
        ExampleRegistry.entries.forall(_.descriptor.sources.nonEmpty),
        ExampleRegistry.entries.forall(_.descriptor.sources.forall(_.path.nonEmpty)),
        ExampleRegistry.entries.forall(_.descriptor.sources.forall(_.region.nonEmpty))
      )
    },
    test("derives collision-free DOM and topic ids from page and directive identity") {
      val examples = ExampleRegistry.instanceId("/examples", "counter")
      val learn    = ExampleRegistry.instanceId("/learn", "counter")
      assertTrue(
        examples != learn,
        examples.startsWith("docs-example-counter-"),
        !examples.startsWith("lv:"),
        ExampleRegistry.topic("/examples", "counter") == s"lv:$examples"
      )
    },
    test("uses explicit counter message and model projectors") {
      val counter = ExampleRegistry.get("counter").get
      assertTrue(
        counter.resetMessage == CounterExample.Msg.Reset,
        counter.resetControlLabel == "Reset",
        counter.projectMessage(CounterExample.Msg.Reset).exists(_.summary == "Reset the count"),
        counter.projectMessage(CounterExample.Msg.Increment)
          .flatMap(_.scalaValue).contains("Msg.Increment"),
        counter.projectMessage("reset").isEmpty,
        counter.projectModel(CounterExample.Model(2)).exists(
          _.fields.contains("count" -> "2")
        ),
        counter.projectModel(CounterExample.Model(2))
          .flatMap(_.scalaValue).contains("Model(count = 2)"),
        counter.projectModel(2).isEmpty
      )
    },
    test("uses explicit shopping cart reset and trace projectors") {
      val cart = ExampleRegistry.get("shopping-cart").get
      val model = ShoppingCartExample.Model.empty
        .add(ShoppingCartExample.Product.Coffee)
        .add(ShoppingCartExample.Product.Coffee)
      assertTrue(
        cart.resetMessage == ShoppingCartExample.Msg.Clear,
        cart.resetControlLabel == "Clear",
        cart.projectMessage(ShoppingCartExample.Msg.Add(ShoppingCartExample.Product.Coffee))
          .exists(_.fields.contains("product" -> "coffee")),
        cart.projectMessage("add coffee").isEmpty,
        cart.projectModel(model).exists(_.fields.contains("itemCount" -> "2")),
        cart.projectModel(model).exists(_.fields.contains("total" -> "$25.98")),
        cart.projectModel(2).isEmpty
      )
    },
    test("keeps activity stream internals out of explicit trace projections") {
      val activityStream = ExampleRegistry.get("activity-stream").get
      assertTrue(
        activityStream.resetMessage == ActivityStreamExample.Msg.Reset,
        activityStream.resetControlLabel == "Reset activity stream",
        activityStream.projectMessage(ActivityStreamExample.Msg.Add)
          .exists(_.summary == "Insert one activity"),
        activityStream.projectMessage(ActivityStreamExample.Msg.Delete(2))
          .exists(trace =>
            trace.scalaValue.contains("Msg.Delete(2)") &&
              trace.fields.contains("activityId" -> "2")
          ),
        activityStream.projectMessage("add").isEmpty
      )
    },
    test("projects managed work without report, error, or clock payloads") {
      val async = ExampleRegistry.get("async-report").get
      val clock = ExampleRegistry.get("subscription-clock").get
      val failure = new RuntimeException("private service details")
      assertTrue(
        async.resetMessage == AsyncReportExample.Msg.Reset,
        async.projectMessage(
          AsyncReportExample.Msg.ReportCompleted(LiveAsyncResult.Failed(failure))
        ).exists(_.summary == "Async report failed"),
        !async.projectMessage(
          AsyncReportExample.Msg.ReportCompleted(LiveAsyncResult.Failed(failure))
        ).exists(_.toString.contains("private service details")),
        async.projectModel(
          AsyncReportExample.Model(AsyncValue.ok(
            AsyncReportExample.Report("Private report", 3, "Private summary")
          ))
        ).exists(_.fields == Vector("state" -> "succeeded")),
        !async.projectModel(
          AsyncReportExample.Model(AsyncValue.ok(
            AsyncReportExample.Report("Private report", 3, "Private summary")
          ))
        ).exists(_.toString.contains("Private report")),
        clock.resetMessage == SubscriptionClockExample.Msg.Reset,
        clock.projectMessage(SubscriptionClockExample.Msg.Tick(java.time.Instant.EPOCH))
          .exists(_.summary == "Receive one clock tick"),
        clock.projectModel(
          SubscriptionClockExample.Model(
            SubscriptionClockExample.Mode.EverySecond,
            Some(java.time.Instant.EPOCH),
            2
          )
        ).exists(_.fields == Vector("mode" -> "Every second", "tickCount" -> "2")),
        !clock.projectModel(
          SubscriptionClockExample.Model(lastTick = Some(java.time.Instant.EPOCH))
        ).exists(_.toString.contains("1970"))
      )
    },
    test("redacts browser payloads from explicit trace projections") {
      val browser = ExampleRegistry.get("browser-integration").get
      val model = BrowserInteropExample.Model(
        requestNumber = 2,
        operation = BrowserInteropExample.CopyOperation.Pending("copy-2")
      )
      val projected = browser.projectModel(model).get
      assertTrue(
        browser.resetMessage == BrowserInteropExample.Msg.Reset,
        browser.resetControlLabel == "Reset browser integration",
        browser.projectMessage(BrowserInteropExample.Msg.CopySample)
          .exists(_.summary == "Request a browser clipboard write"),
        projected.fields.contains("requestNumber" -> "2"),
        projected.fields.contains("operation" -> "pending"),
        !projected.toString.contains(BrowserInteropExample.SampleText)
      )
    },
    test("uses explicit lifecycle reset and trace projectors") {
      val lifecycle = ExampleRegistry.get("lifecycle").get
      val model = LifecycleExample.Model(
        connectedMount = true,
        currentTitle = "Attention needed"
      )
      assertTrue(
        lifecycle.resetMessage == LifecycleExample.Msg.Reset,
        lifecycle.resetControlLabel == "Reset example",
        lifecycle.projectMessage(LifecycleExample.Msg.PutNotification)
          .exists(_.summary == "Put a keyed notification"),
        lifecycle.projectMessage("put notification").isEmpty,
        lifecycle.projectModel(model).exists(_.fields.contains("connectedMount" -> "true")),
        lifecycle.projectModel(model).exists(_.fields.contains("currentTitle" -> "Attention needed")),
        lifecycle.projectModel("Attention needed").isEmpty
      )
    },
    test("escapes projected strings as valid Scala literals") {
      val voting = ExampleRegistry.get("voting-components").get
      val projected = voting.projectMessage(
        VotingComponentsExample.Msg.ComponentReported("quote\" slash\\ line\n control\b", 2)
      ).get

      assertTrue(
        projected.scalaValue.exists(_.contains("Msg.ComponentReported(")),
        projected.scalaValue.exists(_.contains("id = \"quote\\\" slash\\\\ line\\n control\\b\"")),
        projected.scalaValue.exists(_.contains("votes = 2")),
        projected.scalaValue.exists(!_.contains("VotingComponentsExample"))
      )
    },
    test("redacts profile form values from explicit trace projectors") {
      val profile = ExampleRegistry.get("profile-form").get
      val model = ProfileFormExample.Model(
        form = ProfileFormExample.Profile.Definition.initial(),
        saved = Some(
          ProfileFormExample.Profile("Ada Lovelace", "secret@example.com", "Private biography")
        )
      )
      val projected = profile.projectModel(model).get
      assertTrue(
        profile.resetMessage == ProfileFormExample.Msg.Reset,
        profile.resetControlLabel == "Reset form",
        profile.projectMessage(ProfileFormExample.Msg.Reset).exists(_.summary == "Reset the form"),
        projected.fields.contains("saved" -> "true"),
        !projected.toString.contains("secret@example.com"),
        !projected.toString.contains("Private biography")
      )
    },
    test("projects navigation presets without raw destination strings") {
      val navigation = ExampleRegistry.get("navigation").get
      assertTrue(
        navigation.resetMessage == NavigationExample.Msg.Reset,
        navigation.projectMessage(
          NavigationExample.Msg.Select(NavigationExample.SearchPreset.TypedForms)
        ).exists(_.fields == Vector("preset" -> "Typed forms")),
        navigation.projectModel(
          NavigationExample.Model(NavigationExample.SearchPreset.Streams)
        ).exists(_.fields == Vector("preset" -> "Streams")),
        !navigation.projectModel(
          NavigationExample.Model(NavigationExample.SearchPreset.Streams)
        ).exists(_.toString.contains("/search"))
      )
    },
    test("projects component reports with stable application identity") {
      val voting = ExampleRegistry.get("voting-components").get
      assertTrue(
        voting.resetMessage == VotingComponentsExample.Msg.Reset,
        voting.projectMessage(VotingComponentsExample.Msg.ComponentReported("scala-vote", 2))
          .exists(_.fields == Vector("componentId" -> "scala-vote", "votes" -> "2")),
        voting.projectMessage(VoteComponent.Msg.Vote).isEmpty
      )
    },
    test("projects report service state without exposing report content") {
      val service = ExampleRegistry.get("service-injection").get
      assertTrue(
        service.resetMessage == ReportsExample.Msg.ResetSelection,
        service.resetControlLabel == "Reset selected report",
        service.projectMessage(ReportsExample.Msg.Select(Reports.fixtures.head))
          .exists(_.fields == Vector("reportId" -> "1")),
        service.projectModel(
          ReportsExample.Model.Loaded(Reports.fixtures, Reports.fixtures.head)
        ).exists(_.fields == Vector("reportCount" -> "2", "selectedReportId" -> "1")),
        !service.projectModel(
          ReportsExample.Model.Loaded(Reports.fixtures, Reports.fixtures.head)
        ).exists(_.toString.contains("Revenue increased"))
      )
    }
  )
end ExampleRegistrySpec
