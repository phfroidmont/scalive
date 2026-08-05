package scalive.docs.examples

import zio.test.*

import scalive.docs.model.ExampleCatalog

object ExampleRegistrySpec extends ZIOSpecDefault:
  private val BehaviorTests = Set("counter-behavior")

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
    }
  )
end ExampleRegistrySpec
