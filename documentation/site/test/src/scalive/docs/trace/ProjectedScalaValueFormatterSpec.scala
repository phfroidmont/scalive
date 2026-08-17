package scalive.docs.trace

import zio.test.*

object ProjectedScalaValueFormatterSpec extends ZIOSpecDefault:
  import ProjectedScalaValue.*

  override def spec = suite("ProjectedScalaValueFormatterSpec")(
    test("renders concise deterministic one-line values and wildcards") {
      val value = constructor("Delete", number(7), wildcard, boolean(true), nullValue)

      assertTrue(ProjectedScalaValueFormatter.format(value) == "Delete(7, _, true, null)")
    },
    test("wraps constructor applications and renders named fields") {
      val value = constructor(
        "Activity",
        field("identifier", number(123)),
        field("category", string("release")),
        field("summary", string("A deliberately long projected summary"))
      )

      assertTrue(
        ProjectedScalaValueFormatter.format(value) ==
          """Activity(
            |  identifier = 123,
            |  category = "release",
            |  summary = "A deliberately long projected summary"
            |)""".stripMargin
      )
    },
    test("escapes strings and never emits ANSI sequences") {
      val value = string("quote=\" slash=\\ line=\n tab=\t ansi=\u001b[31m")
      val rendered = ProjectedScalaValueFormatter.format(value)

      assertTrue(
        rendered == "\"quote=\\\" slash=\\\\ line=\\n tab=\\t ansi=\\u001b[31m\"",
        !rendered.contains("\u001b")
      )
    },
    test("renders named collections") {
      val value = vector(name("Ready"), list(number(1), number(2)), seq(string("done")))

      assertTrue(ProjectedScalaValueFormatter.format(value) == "Vector(Ready, List(1, 2), Seq(\"done\"))")
    },
    test("bounds and truncates tall output") {
      val values = (1 to 40).map(index => constructor("Entry", field("index", number(index))))
      val rendered = ProjectedScalaValueFormatter.format(collection("Entries", values*))

      assertTrue(rendered.linesIterator.size <= 16, rendered.endsWith("..."))
    }
  )
