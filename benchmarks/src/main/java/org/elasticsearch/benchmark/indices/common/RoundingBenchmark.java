/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.indices.common;

import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Fork(2)
@Warmup(iterations = 10)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RoundingBenchmark {
    private static final DateFormatter FORMATTER = DateFormatter.forPattern("date_optional_time");

    @Param(
        {
            "2000-01-01 to 2020-01-01", // A super long range
            "2000-10-01 to 2000-11-01", // A whole month which is pretty believable
            "2000-10-29 to 2000-10-30", // A date right around daylight savings time.
            "2000-06-01 to 2000-06-02"  // A date fully in one time zone. Should be much faster than above.
        }
    )
    public String range;

    @Param({ "java time", "es" })
    public String rounder;

    @Param({ "UTC", "America/New_York" })
    public String zone = null;

    @Param({ "calendar year", "calendar hour", "10d", "5d", "1h" })
    public String interval = null;

    @Param({ "1", "10000", "1000000", "100000000" })
    public int count = 0;

    private long min = 0L;
    private long max = 0L;
    private long[] dates = null;
    private Supplier<Rounding.Prepared> rounderBuilder = null;

    @Setup
    public void buildDates() {
        final String[] r = this.range.split(" to ");
        this.min = RoundingBenchmark.FORMATTER.parseMillis(r[0]);
        this.max = RoundingBenchmark.FORMATTER.parseMillis(r[1]);
        this.dates = new long[this.count];
        long date = this.min;
        final long diff = (this.max - this.min) / this.dates.length;
        for (int i = 0; i < this.dates.length; i++) {
            if (date >= this.max) {
                throw new IllegalStateException("made a bad date [" + date + "]");
            }
            this.dates[i] = date;
            date += diff;
        }
        final Rounding.Builder roundingBuilder;
        if (this.interval.startsWith("calendar ")) {
            roundingBuilder = Rounding.builder(
                DateHistogramAggregationBuilder.DATE_FIELD_UNITS.get(this.interval.substring("calendar ".length()))
            );
        } else {
            roundingBuilder = Rounding.builder(TimeValue.parseTimeValue(this.interval, "interval"));
        }
        final Rounding rounding = roundingBuilder.timeZone(ZoneId.of(this.zone)).build();
        this.rounderBuilder = switch (this.rounder) {
            case "java time" -> rounding::prepareJavaTime;
            case "es" -> () -> rounding.prepare(this.min, this.max);
            default -> throw new IllegalArgumentException("Expected rounder to be [java time] or [es]");
        };
    }

    @Benchmark
    public final void round(final Blackhole bh) {
        final Rounding.Prepared rounder = this.rounderBuilder.get();
        for (int i = 0; i < this.dates.length; i++) {
            bh.consume(rounder.round(this.dates[i]));
        }
    }

    @Benchmark
    public final void nextRoundingValue(final Blackhole bh) {
        final Rounding.Prepared rounder = this.rounderBuilder.get();
        for (int i = 0; i < this.dates.length; i++) {
            bh.consume(rounder.nextRoundingValue(this.dates[i]));
        }
    }
}
