package scalive

import zio.test.*

object EnumEqualitySpec extends ZIOSpecDefault:
  override def spec = suite("EnumEqualitySpec")(
    test("enums whose cases carry no data compare under strict equality") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scala.language.strictEquality
        def first(join: ConnectedJoin)                 = join == ConnectedJoin.First
        def lossless(delivery: SubscriptionDelivery)   = delivery == SubscriptionDelivery.Lossless
        def leaving(scope: NavigationGuardScope)       = scope == NavigationGuardScope.LeavingView
        def selected(mode: LiveUploadMaxEntriesMode)   = mode == LiveUploadMaxEntriesMode.Selected
        def message(kind: LifecycleTurnKind)           = kind == LifecycleTurnKind.Message
        def mount(stage: LifecycleFailureStage)        = stage == LifecycleFailureStage.Mount
        def writer(queue: LifecycleQueue)              = queue == LifecycleQueue.Writer
      """)

      assertTrue(errors.isEmpty)
    }
  )
