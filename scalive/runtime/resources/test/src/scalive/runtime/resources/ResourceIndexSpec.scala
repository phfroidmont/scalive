package scalive.runtime.resources

import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.Epoch
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.contracts.RuntimeIdentityError
import zio.test.*

object ResourceIndexSpec extends ZIOSpecDefault:
  private val lifecycle = LifecycleId(1L)
  private val root      = OwnerId.Root(lifecycle)
  private val component = OwnerId.Component(lifecycle, ComponentInstanceId(1L))
  private val epoch     = Epoch.initial

  override def spec = suite("ResourceIndexSpec")(
    test("separates owners and tagged keys exactly") {
      val async  = ResourceKey.Async("shared")
      val stream = ResourceKey.Stream("shared")
      for
        rootAsync       <- ResourceIndex.empty[String].replace(root, epoch, async, "root-async")
        componentAsync  <- rootAsync.index.replace(component, epoch, async, "component-async")
        componentStream <- componentAsync.index.replace(component, epoch, stream, "component-stream")
      yield assertTrue(
        componentStream.index.get(root, async).contains("root-async"),
        componentStream.index.get(component, async).contains("component-async"),
        componentStream.index.get(component, stream).contains("component-stream"),
        componentStream.index.values == Vector("root-async", "component-async", "component-stream")
      )
    },
    test("replacement advances generations and suppresses stale tokens") {
      val key = ResourceKey.Subscription("updates")
      for
        first  <- ResourceIndex.empty[String].replace(root, epoch, key, "first")
        second <- first.index.replace(root, Epoch(2L), key, "second")
      yield assertTrue(
        first.token.generation == 1L,
        second.token.generation == 2L,
        second.token.ownerEpoch == Epoch(2L),
        second.replaced.contains("first"),
        !second.index.isCurrent(first.token),
        second.index.isCurrent(second.token),
        second.index.values == Vector("second")
      )
    },
    test("key removal preserves generation history") {
      val key = ResourceKey.Stream("items")
      for
        first  <- ResourceIndex.empty[String].replace(root, epoch, key, "first")
        removed = first.index.remove(root, key)
        second <- removed.index.replace(root, epoch, key, "second")
      yield assertTrue(
        second.token.generation == 2L,
        !second.index.isCurrent(first.token),
        second.index.isCurrent(second.token)
      )
    },
    test("key and owner removal return only matching handles") {
      val async  = ResourceKey.Async("load")
      val client = ResourceKey.Client("clock")
      for
        first  <- ResourceIndex.empty[String].replace(root, epoch, async, "root-async")
        second <- first.index.replace(root, epoch, client, "root-client")
        third  <- second.index.replace(component, epoch, async, "component-async")
        keyRemoval = third.index.remove(root, async)
        ownerRemoval = keyRemoval.index.removeOwner(root)
      yield assertTrue(
        keyRemoval.removed.contains("root-async"),
        ownerRemoval.removed == Vector("root-client"),
        ownerRemoval.index.values == Vector("component-async"),
        !ownerRemoval.index.isCurrent(first.token),
        ownerRemoval.index.isCurrent(third.token)
      )
    },
    test("validates positive generations and reports exhaustion") {
      val key = ResourceKey.Upload("avatar")
      val zero = ResourceToken.from(root, epoch, key, 0L)
      val maximum = ResourceToken.from(root, epoch, key, Long.MaxValue).get
      assertTrue(
        zero.isEmpty,
        maximum.advance() == Left(RuntimeIdentityError.Exhausted("resource generation"))
      )
    }
  )
