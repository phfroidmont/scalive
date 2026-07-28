package scalive.examples.services

import zio.*

trait Guestbook:
  def entries: UIO[Vector[Guestbook.Entry]]
  def add(author: String, message: String): UIO[Guestbook.Entry]

object Guestbook:
  final case class Entry(id: Long, author: String, message: String)

  val live: ULayer[Guestbook] =
    ZLayer.fromZIO(
      Ref
        .make(Vector(Entry(1L, "Scalive", "This entry lives in shared server state.")))
        .map { entriesRef =>
          new Guestbook:
            def entries: UIO[Vector[Entry]] = entriesRef.get

            def add(author: String, message: String): UIO[Entry] =
              entriesRef.modify { current =>
                val nextId = current.lastOption.fold(1L)(_.id + 1L)
                val entry  = Entry(nextId, author, message)
                (entry, current :+ entry)
              }
        }
    )
