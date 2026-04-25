package scalive

import zio.test.*

object FormDataSpec extends ZIOSpecDefault:
  override def spec = suite("FormDataSpec")(
    test("preserves ordered repeated fields") {
      val data = FormData.fromUrlEncoded(
        "my_form%5Busers_sort%5D%5B%5D=0&my_form%5Busers_sort%5D%5B%5D=new&name=Alice+Smith"
      )

      assertTrue(
        data.raw == Vector(
          "my_form[users_sort][]" -> "0",
          "my_form[users_sort][]" -> "new",
          "name"                  -> "Alice Smith"
        ),
        data.values("my_form[users_sort][]") == Vector("0", "new"),
        data.string("name") == Some("Alice Smith")
      )
    },
    test("provides a lossy last-value map for existing APIs") {
      val data = FormData.fromUrlEncoded("field=old&field=new&empty=")

      assertTrue(
        data.asMap == Map("field" -> "new", "empty" -> ""),
        data.getOrElse("missing", "fallback") == "fallback"
      )
    },
    test("extracts direct nested fields") {
      val data = FormData.fromUrlEncoded(
        "my_form%5Bname%5D=Test&my_form%5Busers%5D%5B0%5D%5Bname%5D=User+A"
      )

      val nested = data.nested("my_form")

      assertTrue(
        nested.string("name") == Some("Test"),
        nested.string("users[0][name]") == Some("User A")
      )
    }
  )
end FormDataSpec
