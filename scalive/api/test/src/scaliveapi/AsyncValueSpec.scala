package scaliveapi

import zio.test.*

import scalive.*

object AsyncValueSpec extends ZIOSpecDefault:
  private val cause = RuntimeException("sensor offline")

  override def spec = suite("AsyncValueSpec")(
    test("reports a failure and its cause") {
      val failed = AsyncValue.ok(20.5).loading().updated(LiveAsyncResult.Failed(cause))

      assertTrue(
        failed.isFailed,
        failed.failure.contains(cause),
        failed.valueOption.contains(20.5),
        !AsyncValue.ok(20.5).isFailed,
        AsyncValue.ok(20.5).failure.isEmpty,
        AsyncValue.loading[Double].failure.isEmpty
      )
    },
    test("maps the current and previous values and keeps the state") {
      def celsius(value: AsyncValue[Double]) = value.map(degrees => s"$degrees °C")

      assertTrue(
        celsius(AsyncValue.empty) == AsyncValue.Empty,
        celsius(AsyncValue.Loading(Some(20.5))) == AsyncValue.Loading(Some("20.5 °C")),
        celsius(AsyncValue.ok(20.5)) == AsyncValue.Ok("20.5 °C"),
        celsius(AsyncValue.Failed(Some(20.5), cause)) == AsyncValue.Failed(Some("20.5 °C"), cause),
        celsius(AsyncValue.Cancelled(None, Some("stopped"))) ==
          AsyncValue.Cancelled(None, Some("stopped"))
      )
    }
  )
end AsyncValueSpec
