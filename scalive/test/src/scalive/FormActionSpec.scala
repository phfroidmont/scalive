package scalive

import scala.util.Try

import zio.http.*
import zio.http.codec.PathCodec
import zio.test.*

object FormActionSpec extends ZIOSpecDefault:

  override def spec = suite("FormActionSpec")(
    test("derives browser form actions from route patterns") {
      val createSession = FormAction.from(Method.POST / "auth" / "session")
      val search = FormAction.from(
        Method.GET / "users" / PathCodec.string("id"),
        "Ada Lovelace"
      )

      assertTrue(
        createSession.method == FormAction.Method.Post,
        createSession.href == "/auth/session",
        search.method == FormAction.Method.Get,
        search.href == "/users/Ada%20Lovelace"
      )
    },
    test("reports unsupported methods and path encoding failures") {
      val positiveId = PathCodec
        .int("id")
        .transformOrFailRight(identity)(id => Either.cond(id > 0, id, "id must be positive"))
      val unsupported = FormAction.fromEither(Method.PATCH / "users", ())
      val pathFailure = FormAction.fromEither(Method.POST / "users" / positiveId, -1)
      val directFailure = Try(FormAction.from(Method.DELETE / "users"))

      assertTrue(
        unsupported == Left(FormAction.EncodeError.UnsupportedMethod(Method.PATCH)),
        pathFailure == Left(FormAction.EncodeError.Path("id must be positive")),
        directFailure.failed.toOption.exists(_.isInstanceOf[FormAction.EncodingException])
      )
    },
    test("keeps external and unusual targets explicitly unsafe") {
      val action = FormAction.unsafe(
        FormAction.Method.Post,
        "https://identity.example.com/session?return=%2Fprofile"
      )

      assertTrue(
        action.method == FormAction.Method.Post,
        action.href == "https://identity.example.com/session?return=%2Fprofile"
      )
    }
  )
end FormActionSpec
