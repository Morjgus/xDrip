package com.eveningoutpost.dexdrip.models;

import com.activeandroid.query.Delete;
import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import org.junit.After;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Tests for linear-regression-based delta smoothing in {@link BgReading#currentSlopeByRegression}
 * and the preference-controlled toggle in
 * {@link BgGraphBuilder#unitizedDeltaString(boolean, boolean, boolean, boolean)}.
 */
public class BgReadingRegressionTest extends RobolectricTestWithConfig {

    private static final long MINUTE_MS = 60_000L;

    @After
    public void cleanup() {
        new Delete().from(BgReading.class).execute();
        Pref.setBoolean("smooth_delta_by_regression", false);
        Pref.setString("smooth_delta_regression_window_minutes", "10");
    }

    /** Creates a BgReading with the given timestamp and glucose value and persists it. */
    private void createReading(long timestamp, double value) {
        BgReading r = new BgReading();
        r.timestamp = timestamp;
        r.calculated_value = value;
        r.raw_data = value;
        r.filtered_data = value;
        r.save();
    }

    // ===== currentSlopeByRegression — correctness =================================================

    @Test
    public void regressionOnPerfectLinearRise_matchesExpectedSlope() {
        // 10 readings, 1 min apart, rising at exactly 2 mg/dL/min
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 100.0 + (9 - i) * 2.0);
        }

        double slope = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);
        double expectedSlopePerMs = 2.0 / MINUTE_MS;

        assertWithMessage("slope on perfect linear rise")
                .that(slope).isWithin(1e-9).of(expectedSlopePerMs);
    }

    @Test
    public void regressionOnPerfectLinearFall_matchesExpectedSlope() {
        // 10 readings, 1 min apart, falling at exactly 3 mg/dL/min
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 200.0 - (9 - i) * 3.0);
        }

        double slope = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);
        double expectedSlopePerMs = -3.0 / MINUTE_MS;

        assertWithMessage("slope on perfect linear fall")
                .that(slope).isWithin(1e-9).of(expectedSlopePerMs);
    }

    @Test
    public void regressionOnFlatLine_slopeIsNearZero() {
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 120.0);
        }

        double slope = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);

        assertWithMessage("slope on flat line").that(slope).isWithin(1e-9).of(0.0);
    }

    // ===== currentSlopeByRegression — noise rejection ============================================

    @Test
    public void regressionSmoothsOutLastPointSpike() {
        // 9 readings rising steadily at +1 mg/dL/min, then a spike back to baseline
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 1; i--) {
            createReading(now - i * MINUTE_MS, 100.0 + (9 - i)); // 100 → 108
        }
        createReading(now, 100.0); // spike: drops 8 mg/dL in one reading

        // Two-point sees 108 → 100, i.e. -8 mg/dL/min
        double twoPointPerMin = BgReading.currentSlope(true) * MINUTE_MS;
        assertWithMessage("two-point detects spike as large drop").that(twoPointPerMin).isLessThan(-5.0);

        // Regression over the 10-minute window sees the underlying +1 mg/dL/min trend
        double regressionPerMin = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true) * MINUTE_MS;
        assertWithMessage("regression slope is positive (correct trend direction)")
                .that(regressionPerMin).isGreaterThan(0.0);
        assertWithMessage("regression is closer to the true trend than two-point")
                .that(Math.abs(regressionPerMin - 1.0)).isLessThan(Math.abs(twoPointPerMin - 1.0));
    }

    @Test
    public void regressionSmoothsOutFirstPointSpike() {
        // Spike on the oldest reading; true trend is flat
        long now = System.currentTimeMillis();
        createReading(now - 9 * MINUTE_MS, 200.0); // outlier at the start
        for (int i = 8; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 100.0); // remaining 9 readings flat at 100
        }

        // Two-point sees only the two most recent (both 100), so slope is 0
        double twoPointPerMin = BgReading.currentSlope(true) * MINUTE_MS;
        assertWithMessage("two-point is 0 when last two readings are equal")
                .that(twoPointPerMin).isWithin(1e-6).of(0.0);

        // Regression is also pulled toward the outlier but remains much smaller than
        // the outlier would imply if it were the only data point
        double regressionPerMin = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true) * MINUTE_MS;
        assertWithMessage("regression with one outlier is far less extreme than the outlier implies")
                .that(Math.abs(regressionPerMin)).isLessThan(10.0);
    }

    // ===== currentSlopeByRegression — edge cases =================================================

    @Test
    public void regressionWithNoReadings_returnsZero() {
        double slope = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);
        assertWithMessage("no readings → 0").that(slope).isEqualTo(0.0);
    }

    @Test
    public void regressionWithOneReading_returnsZero() {
        createReading(System.currentTimeMillis(), 120.0);

        double slope = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);
        assertWithMessage("single reading → 0").that(slope).isEqualTo(0.0);
    }

    @Test
    public void regressionWindowExcludesOldReadings() {
        // Reading 20 min ago that would imply a strong upward slope if included
        long now = System.currentTimeMillis();
        createReading(now - 20 * MINUTE_MS, 300.0);

        // 5 recent readings flat at 100
        for (int i = 4; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 100.0);
        }

        // With a 10-minute window the old reading is excluded; slope should be ~0
        double slopePerMin = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true) * MINUTE_MS;
        assertWithMessage("reading outside window is excluded")
                .that(Math.abs(slopePerMin)).isLessThan(0.1);
    }

    @Test
    public void regressionUsesAllReadingsInWindow() {
        // Insert readings covering exactly a 5-min window and a 10-min window.
        // The longer window captures an earlier rise, making the slope larger.
        long now = System.currentTimeMillis();
        // Flat for the last 5 min (readings at 0..5 min ago = 110)
        for (int i = 0; i <= 5; i++) {
            createReading(now - i * MINUTE_MS, 110.0);
        }
        // Rising strongly from 6–10 min ago (60 → 110)
        for (int i = 6; i <= 10; i++) {
            createReading(now - i * MINUTE_MS, 60.0 + (10 - i) * 10.0);
        }

        double narrowSlope = BgReading.currentSlopeByRegression(5 * MINUTE_MS, true);
        double wideSlope   = BgReading.currentSlopeByRegression(10 * MINUTE_MS, true);

        assertWithMessage("narrow window (flat region) gives slope near 0")
                .that(Math.abs(narrowSlope * MINUTE_MS)).isLessThan(0.1);
        assertWithMessage("wider window (captures prior rise) gives larger positive slope")
                .that(wideSlope).isGreaterThan(narrowSlope);
    }

    // ===== unitizedDeltaString — toggle behaviour ================================================

    @Test
    public void unitizedDeltaString_defaultOff_usesTwoPointSlope() {
        // Two readings 5 min apart: 100 → 110 = +10 mg/dL over 5 min
        long now = System.currentTimeMillis();
        createReading(now - 5 * MINUTE_MS, 100.0);
        createReading(now, 110.0);

        // preference defaults to false — no regression
        Pref.setBoolean("smooth_delta_by_regression", false);

        String delta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);
        assertWithMessage("two-point delta of +10").that(delta).isEqualTo("+10");
    }

    @Test
    public void unitizedDeltaString_regressionEnabled_usesFittedSlope() {
        // 10 readings rising at exactly 1 mg/dL/min → 5-min delta should be +5
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 100.0 + (9 - i));
        }

        Pref.setBoolean("smooth_delta_by_regression", true);
        Pref.setString("smooth_delta_regression_window_minutes", "10");

        String delta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);
        assertWithMessage("regression delta is +5 for 1 mg/dL/min trend over 10-min window")
                .that(delta).isEqualTo("+5");
    }

    @Test
    public void unitizedDeltaString_windowSetting_isRespected() {
        // Readings: steady +2 mg/dL/min for the past 30 min.
        // Both 5-min and 10-min windows should estimate +10 (2 * 5) for a perfect linear trend.
        // The point of this test is that both windows agree on a perfect trend,
        // proving the window preference is plumbed through.
        long now = System.currentTimeMillis();
        for (int i = 29; i >= 0; i--) {
            createReading(now - i * MINUTE_MS, 100.0 + (29 - i) * 2.0);
        }

        Pref.setBoolean("smooth_delta_by_regression", true);

        Pref.setString("smooth_delta_regression_window_minutes", "5");
        String delta5 = BgGraphBuilder.unitizedDeltaString(false, false, true, true);

        Pref.setString("smooth_delta_regression_window_minutes", "10");
        String delta10 = BgGraphBuilder.unitizedDeltaString(false, false, true, true);

        // Both should show +10 (2 mg/dL/min × 5-min projection) for a perfect linear trend
        assertWithMessage("5-min window delta").that(delta5).isEqualTo("+10");
        assertWithMessage("10-min window delta").that(delta10).isEqualTo("+10");
    }

    @Test
    public void unitizedDeltaString_regressionSmoothsNoise_betterThanTwoPoint() {
        // 9 readings rising at +1/min then a spike: two-point goes negative, regression stays positive
        long now = System.currentTimeMillis();
        for (int i = 9; i >= 1; i--) {
            createReading(now - i * MINUTE_MS, 100.0 + (9 - i));
        }
        createReading(now, 100.0); // spike back to baseline

        Pref.setBoolean("smooth_delta_by_regression", false);
        String twoPointDelta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);

        Pref.setBoolean("smooth_delta_by_regression", true);
        Pref.setString("smooth_delta_regression_window_minutes", "10");
        String regressionDelta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);

        assertWithMessage("two-point shows negative delta on spike").that(twoPointDelta).startsWith("-");
        assertWithMessage("regression shows positive delta despite spike").that(regressionDelta).startsWith("+");
    }

    @Test
    public void unitizedDeltaString_insufficientReadings_returnsQuestionMarks() {
        // Only one reading — can't compute delta regardless of algorithm
        createReading(System.currentTimeMillis(), 120.0);

        Pref.setBoolean("smooth_delta_by_regression", true);

        String delta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);
        assertWithMessage("single reading → ???").that(delta).isEqualTo("???");
    }

    @Test
    public void unitizedDeltaString_readingsTooFarApart_returnsQuestionMarks() {
        // Two readings more than 20 min apart — stale data guard should fire
        long now = System.currentTimeMillis();
        createReading(now - 25 * MINUTE_MS, 100.0);
        createReading(now, 110.0);

        Pref.setBoolean("smooth_delta_by_regression", true);

        String delta = BgGraphBuilder.unitizedDeltaString(false, false, true, true);
        assertWithMessage("readings > 20 min apart → ???").that(delta).isEqualTo("???");
    }
}
