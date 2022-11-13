/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.search.fetch.subphase.FetchSourcePhase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Fork(1)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FilterContentBenchmark {

    @Param({ "cluster_stats", "index_stats", "node_stats" })
    private String type;

    @Param({ "10_field", "half_field", "all_field", "wildcard_field", "10_wildcard_field" })
    private String fieldCount;

    @Param("true")
    private boolean inclusive;

    private BytesReference source = null;
    private XContentParserConfiguration parserConfig = null;
    private Set<String> filters = null;
    private XContentParserConfiguration parserConfigMatchDotsInFieldNames = null;

    @Setup
    public void setup() throws IOException {
        final String sourceFile = switch (this.type) {
            case "cluster_stats" -> "monitor_cluster_stats.json";
            case "index_stats" -> "monitor_index_stats.json";
            case "node_stats" -> "monitor_node_stats.json";
            default -> throw new IllegalArgumentException("Unknown type [" + this.type + "]");
        };
        this.source = this.readSource(sourceFile);
        this.filters = this.buildFilters();
        this.parserConfig = this.buildParseConfig(false);
        this.parserConfigMatchDotsInFieldNames = this.buildParseConfig(true);
    }

    private Set<String> buildFilters() {
        final Map<String, Object> flattenMap = Maps.flatten(XContentHelper.convertToMap(this.source, true, XContentType.JSON).v2(), false, true);
        final Set<String> keys = flattenMap.keySet();
        final AtomicInteger count = new AtomicInteger();
        return switch (this.fieldCount) {
            case "10_field" -> keys.stream().filter(key -> 0 == count.getAndIncrement() % 5).limit(10).collect(Collectors.toSet());
            case "half_field" -> keys.stream().filter(key -> 0 == count.getAndIncrement() % 2).collect(Collectors.toSet());
            case "all_field" -> new HashSet<>(keys);
            case "wildcard_field" -> new HashSet<>(List.of("*stats"));
            case "10_wildcard_field" -> Set.of(
                "*stats.nodes*",
                "*stats.ind*",
                "*sta*.shards",
                "*stats*.xpack",
                "*stats.*.segments",
                "*stat*.*.data*",
                this.inclusive ? "*stats.**.request_cache" : "*stats.*.request_cache",
                this.inclusive ? "*stats.**.stat" : "*stats.*.stat",
                this.inclusive ? "*stats.**.threads" : "*stats.*.threads",
                "*source_node.t*"
            );
            default -> throw new IllegalArgumentException("Unknown type [" + this.type + "]");
        };
    }

    @Benchmark
    public BytesReference filterWithParserConfigCreated() throws IOException {
        return this.filter(parserConfig);
    }

    @Benchmark
    public BytesReference filterWithParserConfigCreatedMatchDotsInFieldNames() throws IOException {
        return this.filter(parserConfigMatchDotsInFieldNames);
    }

    @Benchmark
    public BytesReference filterWithNewParserConfig() throws IOException {
        final XContentParserConfiguration contentParserConfiguration = this.buildParseConfig(false);
        return this.filter(contentParserConfiguration);
    }

    @Benchmark
    public BytesReference filterWithMap() throws IOException {
        final Map<String, Object> sourceMap = XContentHelper.convertToMap(this.source, false).v2();
        final String[] includes;
        final String[] excludes;
        if (this.inclusive) {
            includes = this.filters.toArray(Strings.EMPTY_ARRAY);
            excludes = null;
        } else {
            includes = null;
            excludes = this.filters.toArray(Strings.EMPTY_ARRAY);
        }
        final Map<String, Object> filterMap = XContentMapValues.filter(sourceMap, includes, excludes);
        return FetchSourcePhase.objectToBytes(filterMap, XContentType.JSON, Math.min(1024, this.source.length()));
    }

    @Benchmark
    public BytesReference filterWithBuilder() throws IOException {
        final BytesStreamOutput streamOutput = new BytesStreamOutput(Math.min(1024, this.source.length()));
        final Set<String> includes;
        final Set<String> excludes;
        if (this.inclusive) {
            includes = this.filters;
            excludes = Set.of();
        } else {
            includes = Set.of();
            excludes = this.filters;
        }
        final XContentBuilder builder = new XContentBuilder(
            XContentType.JSON.xContent(),
            streamOutput,
            includes,
            excludes,
            XContentType.JSON.toParsedMediaType()
        );
        try (final XContentParser parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, this.source.streamInput())) {
            builder.copyCurrentStructure(parser);
            return BytesReference.bytes(builder);
        }
    }

    private XContentParserConfiguration buildParseConfig(final boolean matchDotsInFieldNames) {
        final Set<String> includes;
        final Set<String> excludes;
        if (this.inclusive) {
            includes = this.filters;
            excludes = null;
        } else {
            includes = null;
            excludes = this.filters;
        }
        return XContentParserConfiguration.EMPTY.withFiltering(includes, excludes, matchDotsInFieldNames);
    }

    private BytesReference filter(final XContentParserConfiguration contentParserConfiguration) throws IOException {
        try (final BytesStreamOutput os = new BytesStreamOutput()) {
            final XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), os);
            try (final XContentParser parser = XContentType.JSON.xContent().createParser(contentParserConfiguration, this.source.streamInput())) {
                if (null != parser.nextToken()) {
                    builder.copyCurrentStructure(parser);
                }
                return BytesReference.bytes(builder);
            }
        }
    }

    private BytesReference readSource(final String fileName) throws IOException {
        return Streams.readFully(FilterContentBenchmark.class.getResourceAsStream(fileName));
    }
}
