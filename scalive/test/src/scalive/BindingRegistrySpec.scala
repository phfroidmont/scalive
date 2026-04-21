package scalive

import zio.test.*

object BindingRegistrySpec extends ZIOSpecDefault:

  private def keyedClickView(keys: List[String]): HtmlElement =
    ul(
      keys.splitBy(identity) { (_, key) =>
        li(idAttr := key, phx.onClick(s"msg-$key"), key)
      }
    )

  private def messageToId(
    bindings: Map[String, Map[String, String] => Either[String, String]]
  ): Map[String, String] =
    bindings.flatMap { case (id, handler) =>
      handler(Map.empty).toOption.map(message => message -> id)
    }

  override def spec = suite("BindingRegistrySpec")(
    test("keyed bindings keep stable IDs across reorder") {
      val before = BindingRegistry.collect[String](keyedClickView(List("a", "b", "c")))
      val after  = BindingRegistry.collect[String](keyedClickView(List("c", "a", "b")))

      val beforeByMessage = messageToId(before)
      val afterByMessage  = messageToId(after)

      assertTrue(
        beforeByMessage == afterByMessage,
        beforeByMessage.values.forall(_.matches("b[0-9a-f]{20}"))
      )
    },
    test("JS.push bindings resolve deterministically") {
      val view = button(phx.onClick(JS.push("one").push("two")), "trigger")

      val first  = BindingRegistry.collect[String](view)
      val second = BindingRegistry.collect[String](view)

      assertTrue(
        first.keySet == second.keySet,
        first.values.flatMap(_(Map.empty).toOption).toSet == Set("one", "two")
      )
    }
  )
end BindingRegistrySpec
