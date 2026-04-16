package scalive

import utest.*

object ComponentsSpec extends TestSuite:

  val tests = Tests {
    test("focusWrap helper") {
      val el = focusWrap("dialog", cls := "wrapper")(
        button("Save"),
        button("Cancel")
      )

      val result = HtmlBuilder.build(el)

      assert(
        result ==
          "<div id=\"dialog\" phx-hook=\"Phoenix.FocusWrap\" class=\"wrapper\"><span id=\"dialog-start\" tabindex=\"0\" aria-hidden=\"true\"></span><button>Save</button><button>Cancel</button><span id=\"dialog-end\" tabindex=\"0\" aria-hidden=\"true\"></span></div>"
      )
    }
  }
