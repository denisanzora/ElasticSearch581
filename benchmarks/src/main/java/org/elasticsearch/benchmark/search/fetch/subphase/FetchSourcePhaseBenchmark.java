package org.elasticsearch.benchmark.search.fetch.subphase;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.FetchSourcePhase;
import org.elasticsearch.search.lookup.SourceLookup;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FetchSourcePhaseBenchmark {
    private BytesReference sourceBytes;
    private FetchSourceContext fetchContext;
    private Set<String> includesSet;
    private Set<String> excludesSet;
    private XContentParserConfiguration parserConfig;

    @Param({ "tiny", "short", "one_4k_field", "one_4m_field" })
    private String source;
    @Param("message")
    private String includes;
    @Param("")
    private String excludes;

    @Setup
    public void setup() throws IOException {
        this.sourceBytes = switch (this.source) {
            case "tiny" -> new BytesArray("{\"message\": \"short\"}");
            case "short" -> this.read300BytesExample();
            case "one_4k_field" -> this.buildBigExample("huge".repeat(1024));
            case "one_4m_field" -> this.buildBigExample("huge".repeat(1024 * 1024));
            default -> throw new IllegalArgumentException("Unknown source [" + this.source + "]");
        };
        this.fetchContext = FetchSourceContext.of(
            true,
            Strings.splitStringByCommaToArray(this.includes),
            Strings.splitStringByCommaToArray(this.excludes)
        );
        this.includesSet = Set.of(this.fetchContext.includes());
        this.excludesSet = Set.of(this.fetchContext.excludes());
        this.parserConfig = XContentParserConfiguration.EMPTY.withFiltering(this.includesSet, this.excludesSet, false);
    }

    private BytesReference read300BytesExample() throws IOException {
        return Streams.readFully(FetchSourcePhaseBenchmark.class.getResourceAsStream("300b_example.json"));
    }

    private BytesReference buildBigExample(final String extraText) throws IOException {
        String bigger = this.read300BytesExample().utf8ToString();
        bigger = "{\"huge\": \"" + extraText + "\"," + bigger.substring(1);
        return new BytesArray(bigger);
    }

    @Benchmark
    public BytesReference filterObjects() throws IOException {
        final SourceLookup lookup = new SourceLookup(new SourceLookup.BytesSourceProvider(this.sourceBytes));
        final Object value = lookup.filter(this.fetchContext);
        return FetchSourcePhase.objectToBytes(value, XContentType.JSON, Math.min(1024, lookup.internalSourceRef().length()));
    }

    @Benchmark
    public BytesReference filterXContentOnParser() throws IOException {
        final BytesStreamOutput streamOutput = new BytesStreamOutput(Math.min(1024, this.sourceBytes.length()));
        final XContentBuilder builder = new XContentBuilder(XContentType.JSON.xContent(), streamOutput);
        try (final XContentParser parser = XContentType.JSON.xContent().createParser(this.parserConfig, this.sourceBytes.streamInput())) {
            builder.copyCurrentStructure(parser);
            return BytesReference.bytes(builder);
        }
    }

    @Benchmark
    public BytesReference filterXContentOnBuilder() throws IOException {
        final BytesStreamOutput streamOutput = new BytesStreamOutput(Math.min(1024, this.sourceBytes.length()));
        final XContentBuilder builder = new XContentBuilder(
            XContentType.JSON.xContent(),
            streamOutput,
                this.includesSet,
                this.excludesSet,
            XContentType.JSON.toParsedMediaType()
        );
        try (
                final XContentParser parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, this.sourceBytes.streamInput())
        ) {
            builder.copyCurrentStructure(parser);
            return BytesReference.bytes(builder);
        }
    }
}
