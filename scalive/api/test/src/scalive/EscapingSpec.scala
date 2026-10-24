package scalive

import zio.test.*

object EscapingSpec extends ZIOSpecDefault:
  override def spec = suite("EscapingSpec")(
    test("escapes markup characters and drops control characters but newlines and tabs") {
      assertTrue(
        Escaping.escape("a < b > c & \"d\"") == "a &lt; b &gt; c &amp; &quot;d&quot;",
        Escaping.escape("line\nnext\r\ttab\u0000\u001f end") == "line\nnext\r\ttab end"
      )
    },
    test("returns text with nothing to escape as it is") {
      val text = "plain text, with 'quotes' and unicode é\n"
      assertTrue(Escaping.escape(text) eq text)
    }
  )
