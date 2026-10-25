package scalive.render

import zio.test.*

object StreamRowCursorSpec extends ZIOSpecDefault:
  private val previous = Vector("a", "b", "c", "d")

  private def positions(
    current: Vector[String],
    inserted: Set[String] = Set.empty,
    deleted: Int = 0
  ): Vector[Int] =
    val cursor = StreamRowCursor[String](previous, identity, inserted, deleted)
    current.map(cursor.indexOf)

  override def spec = suite("StreamRowCursorSpec")(
    test("finds each kept row past an appended or a prepended one") {
      assertTrue(
        positions(previous :+ "e", inserted = Set("e")) == Vector(0, 1, 2, 3, -1),
        positions("e" +: previous, inserted = Set("e")) == Vector(-1, 0, 1, 2, 3)
      )
    },
    test("finds each kept row past deleted and pruned ones") {
      assertTrue(
        positions(Vector("a", "c", "d"), deleted = 1) == Vector(0, 2, 3),
        // The first row was pruned when the last one was inserted.
        positions(Vector("b", "c", "d", "e"), inserted = Set("e")) == Vector(1, 2, 3, -1)
      )
    },
    test("gives a row updated in place no previous position, and finds the rows after it") {
      assertTrue(positions(previous, inserted = Set("b")) == Vector(0, -1, 2, 3))
    },
    test("finds rows beyond its reach, and gives an unknown row no previous position") {
      assertTrue(
        positions(Vector("d", "a")) == Vector(3, 0),
        positions(Vector("x", "a")) == Vector(-1, 0)
      )
    }
  )
end StreamRowCursorSpec
