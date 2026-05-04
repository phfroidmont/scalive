package scalive

import zio.test.*

import scalive.RenderSnapshot.*

object RenderSnapshotSpec extends ZIOSpecDefault:

  override def spec = suite("RenderSnapshotSpec")(
    test("interns static fragments for matching templates") {
      val first  = RenderSnapshot.compile(div(span("first")))
      val second = RenderSnapshot.compile(div(span("second")))

      assertTrue(
        first.root.static.asInstanceOf[AnyRef] eq second.root.static.asInstanceOf[AnyRef]
      )
    },
    test("treats boolean presence attrs as dynamic slots") {
      val off = RenderSnapshot.compile(form(phx.triggerAction := false, button("Submit")))
      val on  = RenderSnapshot.compile(form(phx.triggerAction := true, button("Submit")))
      val diff = TreeDiff.diff(off, on)

      val patchedAttr = diff match
        case Diff.Tag(static, dynamic, _, _, _, _, _, _) =>
          static.isEmpty && dynamic.exists(_.diff == Diff.Value(" phx-trigger-action"))
        case _ => false

      assertTrue(off.root.static == on.root.static, patchedAttr)
    },
    test("keeps keyed entry fingerprints for non-stream comprehensions") {
      val compiled = RenderSnapshot.compile(
        ul(
          Mod.Content.Keyed(
            entries = Vector(
              Mod.Content.Keyed.Entry("item-1", li("one"))
            )
          )
        )
      )

      val keyedNode = compiled.root.slots.collectFirst { case KeyedSlot(node) => node }

      assertTrue(
        keyedNode.exists(_.entryFingerprints.exists(_.nonEmpty))
      )
    },
    test("elides keyed entry fingerprints for stream comprehensions") {
      val stream = Diff.Stream(
        ref = "0",
        inserts = Vector(
          Diff.StreamInsert(
            domId = "item-1",
            at = -1,
            limit = None,
            updateOnly = None
          )
        ),
        deleteIds = Vector.empty,
        reset = false
      )

      val compiled = RenderSnapshot.compile(
        ul(
          Mod.Content.Keyed(
            entries = Vector(
              Mod.Content.Keyed.Entry("item-1", li("one"))
            ),
            stream = Some(stream)
          )
        )
      )

      val keyedNode = compiled.root.slots.collectFirst { case KeyedSlot(node) => node }

      assertTrue(
        keyedNode.exists(_.entryFingerprints.isEmpty)
      )
    }
  )
end RenderSnapshotSpec
