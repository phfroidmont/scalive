package scalive

import zio.test.*

object FormOverlaySpec extends ZIOSpecDefault:
  final private case class Course(title: String, year: String)
  final private case class Application(name: String, tags: Vector[String], courses: Vector[Course])

  private val Root        = FormRoot("application")
  private val Name        = Root.text("name")
  private val Tags        = Root.texts("tags")
  private val Courses     = Root.rows("courses")
  private val Title       = Courses.text("title")
  private val Year        = Courses.text("year")
  private val CourseRows  = Courses.product[Course]((title = Title, year = Year))
  private val Definition  =
    Root.product[Application]((name = Name, tags = Tags, courses = CourseRows))
  private val First       = FormRowKey.from[Courses.type]("first").toOption.get
  private val Second      = FormRowKey.from[Courses.type]("second").toOption.get
  private val Assignments = Vector(
    Name.initial("Ada"),
    Tags.initial(Vector("math", "engines")),
    CourseRows.initial(
      CourseRows.row(First)(Title.initial("Analysis"), Year.initial("1843")),
      CourseRows.row(Second)(Title.initial("Notes"))
    )
  )

  override def spec = suite("FormOverlaySpec")(
    test("overlaying assignments onto an empty payload projects back to the same values") {
      val (data, names) = Definition.overlay(FormData.empty, Assignments)
      val projection    = Definition.project(data)
      val sameValues    = projection.values == Definition.initial(Assignments*).values
      val noErrors      = projection.structuralErrors.isEmpty

      assertTrue(
        sameValues,
        noErrors,
        names.contains("application[name]"),
        names.contains("application[courses][second][title]")
      )
    },
    test("assignments replace the fields and groups they set and keep the rest") {
      val rendered = FormData(
        Vector(
          "application[name]"                         -> "Grace",
          "application[tags][]"                       -> "navy",
          "application[courses][old][_scalive_row]"   -> "1",
          "application[courses][old][title]"          -> "Compilers",
          "application[unrelated]"                    -> "kept"
        )
      )
      val (data, _) = Definition.overlay(
        rendered,
        Vector(
          Name.initial("Ada"),
          CourseRows.initial(CourseRows.row(First)(Title.initial("Analysis")))
        )
      )

      assertTrue(
        data.values("application[name]") == Vector("Ada"),
        data.values("application[tags][]") == Vector("navy"),
        data.values("application[unrelated]") == Vector("kept"),
        !data.raw.exists(_._1.startsWith("application[courses][old]")),
        data.values("application[courses][first][title]") == Vector("Analysis"),
        Definition.declares("application[courses][first][title]"),
        Definition.declares("application[name]"),
        !Definition.declares("application[unrelated]")
      )
    }
  )
