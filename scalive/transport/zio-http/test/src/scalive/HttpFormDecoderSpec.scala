package scalive

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

object HttpFormDecoderSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  private val csrf = LiveSecurity(config).csrf

  private def formRequest(value: String): Request =
    Request.post(
      URL.root,
      Body.fromString(value).contentType(MediaType.application.`x-www-form-urlencoded`)
    )

  private def validRequest(value: String): UIO[Request] =
    ZioHttpSecurity.issueCsrf(config).map { issued =>
      formRequest(s"${CsrfProtection.ParamName}=${issued.token}&$value")
        .addCookie(Cookie.Request(CsrfProtection.CookieName, issued.cookieToken))
    }

  override def spec = suite("HttpFormDecoder.respond")(
    test("successful decoding invokes only the success callback") {
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name"), 1024, csrf)

      for
        request  <- validRequest("name=Ada")
        trace    <- Ref.make(Vector.empty[String])
        response <- decoder.respond(
                      request,
                      _ => trace.update(_ :+ "validation").as(Response.badRequest),
                      error => trace.update(_ :+ s"rejected:${error.code}")
                    )(name => trace.update(_ :+ s"success:$name").as(Response.ok))
        events <- trace.get
      yield assertTrue(response.status == Status.Ok, events == Vector("success:Ada"))
    },
    test("semantic validation observes rejection before invoking validation exactly once") {
      val issue   = FieldIssue("Name is required")
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name", issue), 1024, csrf)

      for
        request  <- validRequest("name=")
        trace    <- Ref.make(Vector.empty[String])
        seen     <- Ref.make(Option.empty[FormErrors[?]])
        response <- decoder.respond(
                      request,
                      errors =>
                        trace.update(_ :+ "validation") *>
                          seen.set(Some(errors)) *>
                          ZIO.succeed(Response.badRequest),
                      error => trace.update(_ :+ s"rejected:${error.code}")
                    )(_ => trace.update(_ :+ "success").as(Response.ok))
        events <- trace.get
        errors <- seen.get
      yield assertTrue(
        response.status == Status.BadRequest,
        events == Vector("rejected:validation", "validation"),
        errors.exists(_.all.map(_.issue) == Vector(issue))
      )
    },
    test(
      "validation callback is deferred, can require an environment, and preserves typed failure"
    ) {
      val decoder     = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name"), 1024, csrf)
      val invocations = new AtomicInteger(0)

      for
        request <- validRequest("name=")
        trace   <- Ref.make(Vector.empty[String])
        effect = decoder.respond[Response, String](
                   request,
                   _ =>
                     invocations.incrementAndGet()
                     ZIO.service[Response] *>
                       trace.update(_ :+ "validation") *>
                       ZIO.fail("validation failed")
                   ,
                   error => trace.update(_ :+ s"rejected:${error.code}")
                 )(_ => trace.update(_ :+ "success").as(Response.ok))
        before            <- trace.get
        invocationsBefore <- ZIO.succeed(invocations.get())
        result            <- effect.provideEnvironment(ZEnvironment(Response.forbidden)).either
        after             <- trace.get
      yield assertTrue(
        before.isEmpty,
        invocationsBefore == 0,
        invocations.get() == 1,
        result == Left("validation failed"),
        after == Vector("rejected:validation", "validation")
      )
    },
    test("default observer supports both effectful response handlers") {
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name"), 1024, csrf)

      for
        request  <- validRequest("name=")
        response <- decoder.respond(request, _ => ZIO.succeed(Response.badRequest))(_ =>
                      ZIO.succeed(Response.ok)
                    )
        valid  <- validRequest("name=Ada")
        result <- decoder
                    .respond(valid, _ => ZIO.succeed(Response.badRequest))(_ =>
                      ZIO.serviceWithZIO[Response](_ => ZIO.fail("success failed"))
                    ).provideEnvironment(ZEnvironment(Response.ok)).either
      yield assertTrue(response.status == Status.BadRequest, result == Left("success failed"))
    },
    test("effectful success inference preserves environment and typed failures") {
      val decoder  = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name"), 1024, csrf)
      val observed = new AtomicInteger(0)

      for
        request <- validRequest("name=Ada")
        effect = decoder.respond(
                   request,
                   _ => ZIO.succeed(Response.badRequest),
                   _ =>
                     observed.incrementAndGet()
                     ZIO.unit
                 )(_ => ZIO.serviceWithZIO[Response](_ => ZIO.fail("success failed")))
        result <- effect.provideEnvironment(ZEnvironment(Response.ok)).either
      yield assertTrue(result == Left("success failed"), observed.get() == 0)
    },
    test("definition-backed decoding sends invalid submitted forms to the success callback") {
      final case class Profile(name: String)
      val root       = FormRoot("profile")
      val name       = root.text("name").required(FieldIssue("Name is required"))
      val definition = root.product[Profile](Tuple1(name))
      val decoder    = HttpFormDecoder.urlEncoded(definition, 1024, csrf)

      for
        request  <- validRequest("profile%5Bname%5D=")
        trace    <- Ref.make(Vector.empty[String])
        response <-
          decoder.respond(
            request,
            _ => trace.update(_ :+ "validation").as(Response.badRequest),
            _ => trace.update(_ :+ "rejected")
          ) { form =>
            trace
              .update(_ :+ "success").as(
                if form.result.isLeft && form.interaction.visibility == ErrorVisibility.All then
                  Status.UnprocessableEntity.toResponse
                else Response.ok
              )
          }
        events <- trace.get
      yield assertTrue(response.status == Status.UnprocessableEntity, events == Vector("success"))
    },
    test("transport, representation, and CSRF rejections only invoke the observer") {
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.formData, 1024, csrf)

      for
        issued <- ZioHttpSecurity.issueCsrf(config)
        invalidCsrf = formRequest(s"${CsrfProtection.ParamName}=bad")
                        .addCookie(Cookie.Request(CsrfProtection.CookieName, issued.cookieToken))
        readFailure = Body
                        .fromStreamChunked(
                          ZStream.fail(new RuntimeException("read failed")): ZStream[
                            Any,
                            Throwable,
                            Byte
                          ]
                        )
                        .contentType(MediaType.application.`x-www-form-urlencoded`)
        cases: Vector[(String, (Request, Status))] =
          Vector(
            "missing csrf"      -> (formRequest("name=Ada"), Status.Forbidden),
            "invalid csrf"      -> (invalidCsrf, Status.Forbidden),
            "oversized"         -> (formRequest("x" * 1025), Status.RequestEntityTooLarge),
            "unsupported media" -> (
              Request.post(URL.root, Body.fromString("name=Ada")),
              Status.UnsupportedMediaType
            ),
            "malformed URL encoding" -> (formRequest("name=%ZZ"), Status.BadRequest),
            "body read failure"      -> (
              Request.post(URL.root, readFailure),
              Status.BadRequest
            )
          )
        results <- ZIO.foreach(cases) { case (label, (request, expectedStatus)) =>
                     for
                       trace    <- Ref.make(Vector.empty[String])
                       response <- decoder.respond(
                                     request,
                                     _ => trace.update(_ :+ "validation").as(Response.badRequest),
                                     error => trace.update(_ :+ s"rejected:${error.code}")
                                   )(_ => trace.update(_ :+ "success").as(Response.ok))
                       events <- trace.get
                     yield (label, response.status, expectedStatus, events)
                   }
        failures = results.filterNot { case (_, actual, expected, events) =>
                     actual == expected && events.size == 1 && events.head.startsWith("rejected:")
                   }
      yield assertTrue(failures.isEmpty)
      end for
    },
    test("an observer defect prevents the validation callback") {
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.requiredString("name"), 1024, csrf)
      val validationInvocations = new AtomicInteger(0)

      for
        request <- validRequest("name=")
        exit    <- decoder
                  .respond(
                    request,
                    _ =>
                      validationInvocations.incrementAndGet()
                      ZIO.succeed(Response.badRequest)
                    ,
                    _ => ZIO.dieMessage("observer failed")
                  )(_ => ZIO.succeed(Response.ok))
                  .exit
      yield assertTrue(exit.isFailure, validationInvocations.get() == 0)
    }
  )
end HttpFormDecoderSpec
