package scaliveapi

import zio.test.*

import scalive.*
import scalive.FormPath.RepresentationError
import scalive.FormPathSegment.*

object FormPathSpec extends ZIOSpecDefault:
  def spec = suite("FormPathSpec")(
    test("round-trips names containing explicit array segments") {
      val parsed = FormPath.parse("users_sort[]")

      assertTrue(
        parsed.map(_.name) == Right("users_sort[]"),
        parsed.map(_.segments) == Right(Vector(Name("users_sort"), Array))
      )
    },
    test("supports trusted name and explicit segment constructors") {
      val names    = FormPath("user", "address") / "city"
      val explicit = FormPath(Name("user"), Name("tags"), Array)

      assertTrue(
        names.name == "user[address][city]",
        names.startsWith(FormPath("user", "address")),
        explicit.name == "user[tags][]",
        explicit.array.name == "user[tags][][]"
      )
    },
    test("rejects malformed bracket syntax without normalization") {
      val malformed = Vector(
        "[user]"      -> RepresentationError.MissingRootName,
        "user]"       -> RepresentationError.UnexpectedClosingBracket(4),
        "user[name"   -> RepresentationError.UnterminatedBracket(4),
        "user[[name]]" -> RepresentationError.UnexpectedOpeningBracket(5),
        "user[name]x" -> RepresentationError.ExpectedOpeningBracket(10),
        "user[name]]" -> RepresentationError.UnexpectedClosingBracket(10)
      )

      assertTrue(malformed.forall { case (value, expected) =>
        FormPath.parse(value) == Left(expected)
      }, RepresentationError.UnterminatedBracket(4).code == "unterminated_bracket")
    },
    test("returns errors rather than throwing for every malformed representation") {
      val inputs = Vector(null, "", "[", "]", "a[", "a]", "a[[b]", "a[b]]", "a[b]tail")

      assertTrue(inputs.forall { input =>
        scala.util.Try(FormPath.parse(input)).toOption.exists(_.isLeft)
      })
    },
    test("bounds ordinary and custom parsing") {
      val shallow = FormPath.ParseLimits(maxDepth = 2, maxSegmentLength = 3)
      val tooLongForDefault = "x" * (FormPath.ParseLimits.default.maxSegmentLength + 1)

      assertTrue(
        FormPath.parse(tooLongForDefault) == Left(
          RepresentationError.SegmentTooLong(0, FormPath.ParseLimits.default.maxSegmentLength)
        ),
        FormPath.parse("abcd", shallow) == Left(RepresentationError.SegmentTooLong(0, 3)),
        FormPath.parse("a[b][c]", shallow) == Left(RepresentationError.PathTooDeep(2)),
        FormPath.parse("a", FormPath.ParseLimits(0, 3)) == Left(
          RepresentationError.InvalidLimits(0, 3)
        ),
        FormPath.parse("a", null) == Left(RepresentationError.NullLimits)
      )
    },
    test("produces injective DOM-safe ids for formerly colliding paths") {
      val paths = Vector(
        FormPath("a_b", "c"),
        FormPath("a", "b_c"),
        FormPath("tags"),
        FormPath(Name("tags"), Array),
        FormPath("å", "名")
      )
      val ids = paths.map(_.id)

      assertTrue(
        ids.distinct.size == ids.size,
        ids.forall(_.matches("[A-Za-z][A-Za-z0-9_-]*"))
      )
    }
  )
end FormPathSpec
