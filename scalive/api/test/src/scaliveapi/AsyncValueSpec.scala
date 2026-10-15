package scaliveapi

import zio.test.*

import scalive.*

object AsyncValueSpec extends ZIOSpecDefault:
  private enum SensorError:
    case Offline

  private val defect = RuntimeException("sensor driver crashed")

  override def spec = suite("AsyncValueSpec")(
    test("keeps a typed failure apart from a defect, and the last value in both") {
      val reading: AsyncValue[SensorError, Double] = AsyncValue.ok(20.5)
      val failed = reading.loading().updated(LiveAsyncResult.Failed(SensorError.Offline))
      val died   = reading.loading().updated(LiveAsyncResult.Died(defect))

      assertTrue(
        failed == AsyncValue.Failed(Some(20.5), SensorError.Offline),
        failed.isFailed,
        failed.failure.contains(SensorError.Offline),
        died == AsyncValue.Died(Some(20.5), defect),
        died.isFailed,
        died.failure.isEmpty,
        died.valueOption.contains(20.5),
        !reading.isFailed,
        reading.failure.isEmpty,
        AsyncValue.loading[SensorError, Double].failure.isEmpty
      )
    },
    test("maps the current and previous values and keeps the state") {
      def celsius(value: AsyncValue[SensorError, Double]) = value.map(degrees => s"$degrees °C")

      assertTrue(
        celsius(AsyncValue.empty) == AsyncValue.Empty,
        celsius(AsyncValue.Loading(Some(20.5))) == AsyncValue.Loading(Some("20.5 °C")),
        celsius(AsyncValue.ok(20.5)) == AsyncValue.Ok("20.5 °C"),
        celsius(AsyncValue.Failed(Some(20.5), SensorError.Offline)) ==
          AsyncValue.Failed(Some("20.5 °C"), SensorError.Offline),
        celsius(AsyncValue.Died(Some(20.5), defect)) == AsyncValue.Died(Some("20.5 °C"), defect),
        celsius(AsyncValue.Cancelled(None, Some("stopped"))) ==
          AsyncValue.Cancelled(None, Some("stopped"))
      )
    },
    test("offers its operations only as methods") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        val value = AsyncValue.currentValue(AsyncValue.ok(1))
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end AsyncValueSpec
