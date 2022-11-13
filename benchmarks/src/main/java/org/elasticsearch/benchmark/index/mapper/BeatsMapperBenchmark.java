/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.index.mapper;

import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.index.mapper.LuceneDocument;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.SourceToParse;
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BeatsMapperBenchmark {

    @Param("1600172297")
    private long seed;

    private Random random;
    private MapperService mapperService;
    private SourceToParse[] sources;

    @Setup
    public void setUp() throws IOException {
        random = new Random(this.seed);
        mapperService = MapperServiceFactory.create(BeatsMapperBenchmark.readSampleMapping());
        sources = this.generateRandomDocuments(10_000);
    }

    private static String readSampleMapping() throws IOException {
        // Uncompressed mapping is around 1mb and 29k lines.
        // It is unlikely that it will be modified so keeping the compressed version instead to minimize the repo size.
        return BeatsMapperBenchmark.readCompressedMapping("filebeat-mapping-8.1.2.json.gz");
    }

    private static String readCompressedMapping(final String resource) throws IOException {
        try (final var in = new GZIPInputStream(BeatsMapperBenchmark.class.getResourceAsStream(resource))) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    private SourceToParse[] generateRandomDocuments(final int count) {
        final var docs = new SourceToParse[count];
        for (int i = 0; i < count; i++) {
            docs[i] = this.generateRandomDocument();
        }
        return docs;
    }

    private SourceToParse generateRandomDocument() {
        return new SourceToParse(
            UUIDs.randomBase64UUID(),
            new BytesArray(
                "{    \"@timestamp\": "
                    + System.currentTimeMillis()
                    + ",    \"log.file.path\": \""
                    + this.randomFrom("logs-1.log", "logs-2.log", "logs-3.log")
                    + "\",    \"log.level\": \""
                    + "INFO"
                    + "\",    \"log.logger\": \""
                    + "some.package.for.logging.requests"
                    + "\",    \"client.ip\": \""
                    + this.randomIp()
                    + "\",    \"http.request.method\": \""
                    + this.randomFrom("GET", "POST")
                    + "\",    \"http.request.id\": \""
                    + this.random.nextInt()
                    + "\",    \"http.request.bytes\": "
                    + this.random.nextInt(1024)
                    + ",    \"url.path\": \""
                    + this.randomString(1024)
                    + "\",    \"http.response.status_code\": "
                    + this.randomFrom(200, 204, 300, 404, 500)
                    + ",    \"http.response.bytes\": "
                    + this.random.nextInt(1024)
                    + ",    \"http.response.mime_type\": \""
                    + this.randomFrom("application/json", "application/xml")
                    + "\"}"
            ),
            XContentType.JSON
        );
    }

    private String randomIp() {
        return String.valueOf(this.random.nextInt(255)) + '.' + this.random.nextInt(255) + '.' + this.random.nextInt(255) + '.' + this.random.nextInt(255);
    }

    private String randomString(final int maxLength) {
        final var length = this.random.nextInt(maxLength);
        final var builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((byte) (32 + this.random.nextInt(94)));
        }
        return builder.toString();
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private <T> T randomFrom(final T... items) {
        return items[this.random.nextInt(items.length)];
    }

    @Benchmark
    public List<LuceneDocument> benchmarkParseKeywordFields() {
        return this.mapperService.documentMapper().parse(this.randomFrom(this.sources)).docs();
    }
}
