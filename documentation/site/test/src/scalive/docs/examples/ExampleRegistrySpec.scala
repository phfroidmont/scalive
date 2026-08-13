package scalive.docs.examples

import zio.test.*

import scalive.docs.model.ExampleCatalog

object ExampleRegistrySpec extends ZIOSpecDefault:
  private val BehaviorTests =
    Set(
      "counter-behavior",
      "activity-stream-behavior",
      "lifecycle-behavior",
      "profile-form-behavior",
      "shopping-cart-behavior"
    )

  override def spec = suite("ExampleRegistrySpec")(
    test("keeps executable entries aligned with generated descriptors and behavior tests") {
      assertTrue(
        ExampleRegistry.validationErrors.isEmpty,
        ExampleRegistry.entries.map(_.descriptor) == ExampleCatalog.entries,
        ExampleRegistry.entries.map(_.behaviorTestId).toSet == BehaviorTests,
        ExampleRegistry.entries.forall(_.descriptor.source.path.nonEmpty),
        ExampleRegistry.entries.forall(_.descriptor.source.region.nonEmpty)
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
        counter.projectMessage("reset").isEmpty,
        counter.projectModel(CounterExample.Model(2)).exists(
          _.fields.contains("count" -> "2")
        ),
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
        activityStream.projectMessage("add").isEmpty
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
    }
  )
end ExampleRegistrySpec
