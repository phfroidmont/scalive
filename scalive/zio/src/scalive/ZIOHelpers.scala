package scalive

import zio.*

private def normalize[A](value: A | Task[A]): Task[A] =
  value match
    case t: Task[?] @unchecked => t.asInstanceOf[Task[A]]
    case v                     => ZIO.succeed(v.asInstanceOf[A])
