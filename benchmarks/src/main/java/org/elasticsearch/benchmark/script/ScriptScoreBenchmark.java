/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.script;

import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.common.lucene.search.function.ScriptScoreQuery;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.DocReader;
import org.elasticsearch.script.DocValuesDocReader;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.search.lookup.SourceLookup;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A race between Lucene Expressions, Painless, and a hand optimized script
 * implementing a {@link ScriptScoreQuery}.
 */
@Fork(2)
@Warmup(iterations = 10)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(1_000_000)   // The index has a million documents in it.
@State(Scope.Benchmark)
public class ScriptScoreBenchmark {
    private final PluginsService pluginsService = new PluginsService(
        Settings.EMPTY,
        null,
        null,
        Path.of(System.getProperty("plugins.dir"))
    );
    private final ScriptModule scriptModule = new ScriptModule(Settings.EMPTY, this.pluginsService.filterPlugins(ScriptPlugin.class));

    private final Map<String, MappedFieldType> fieldTypes = Map.ofEntries(
        Map.entry("n", new NumberFieldMapper.NumberFieldType("n", NumberFieldMapper.NumberType.LONG, false, false, true, true, null, Map.of(), null, false, null))
    );
    private final IndexFieldDataCache fieldDataCache = new IndexFieldDataCache.None();
    private final Map<String, Set<String>> sourcePaths = Map.of("n", Set.of("n"));
    private final CircuitBreakerService breakerService = new NoneCircuitBreakerService();
    private final SearchLookup lookup = new SearchLookup(
        this.fieldTypes::get,
        (mft, lookup, fdo) -> mft.fielddataBuilder(FieldDataContext.noRuntimeFields("benchmark")).build(this.fieldDataCache, this.breakerService),
        new SourceLookup.ReaderSourceProvider()
    );

    @Param({ "expression", "metal", "painless_cast", "painless_def" })
    private String script;

    @Param("16")
    private double indexingBufferMb;

    private ScoreScript.Factory factory;

    private IndexReader reader;

    @Setup
    public void setupScript() {
        this.factory = switch (this.script) {
            case "expression" -> this.scriptModule.engines.get("expression").compile("test", "doc['n'].value", ScoreScript.CONTEXT, Map.of());
            case "metal" -> this.bareMetalScript();
            case "painless_cast" -> this.scriptModule.engines.get("painless")
                .compile(
                    "test",
                    "((org.elasticsearch.index.fielddata.ScriptDocValues.Longs)doc['n']).value",
                    ScoreScript.CONTEXT,
                    Map.of()
                );
            case "painless_def" -> this.scriptModule.engines.get("painless").compile("test", "doc['n'].value", ScoreScript.CONTEXT, Map.of());
            default -> throw new IllegalArgumentException("Don't know how to implement script [" + this.script + "]");
        };
    }

    @Setup
    public void setupIndex() throws IOException {
        final Path path = Path.of(System.getProperty("tests.index"));
        IOUtils.rm(path);
        final Directory directory = new MMapDirectory(path);
        try (
            final IndexWriter w = new IndexWriter(
                directory,
                new IndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.CREATE).setRAMBufferSizeMB(this.indexingBufferMb)
            )
        ) {
            for (int i = 1; 1_000_000 >= i; i++) {
                w.addDocument(List.of(new SortedNumericDocValuesField("n", i)));
            }
            w.commit();
        }
        this.reader = DirectoryReader.open(directory);
    }

    @Benchmark
    public TopDocs benchmark() throws IOException {
        final TopDocs topDocs = new IndexSearcher(this.reader).search(this.scriptScoreQuery(this.factory), 10);
        if (1_000_000 != topDocs.scoreDocs[0].score) {
            throw new AssertionError("Expected score to be 1,000,000 but was [" + topDocs.scoreDocs[0].score + "]");
        }
        return topDocs;
    }

    private Query scriptScoreQuery(final ScoreScript.Factory factory) {
        final ScoreScript.LeafFactory leafFactory = factory.newFactory(Map.of(), this.lookup);
        return new ScriptScoreQuery(new MatchAllDocsQuery(), null, leafFactory, this.lookup, null, "test", 0, Version.CURRENT);
    }

    private ScoreScript.Factory bareMetalScript() {
        return (params, lookup) -> {
            final MappedFieldType type = this.fieldTypes.get("n");
            final IndexNumericFieldData ifd = (IndexNumericFieldData) lookup.getForField(type, MappedFieldType.FielddataOperation.SEARCH);
            return new ScoreScript.LeafFactory() {
                @Override
                public ScoreScript newInstance(final DocReader docReader) throws IOException {
                    final SortedNumericDocValues values = ifd.load(((DocValuesDocReader) docReader).getLeafReaderContext()).getLongValues();
                    return new ScoreScript(params, null, docReader) {
                        private int docId;

                        @Override
                        public double execute(final ExplanationHolder explanation) {
                            try {
                                values.advance(this.docId);
                                if (1 != values.docValueCount()) {
                                    throw new IllegalArgumentException("script only works when there is exactly one value");
                                }
                                return values.nextValue();
                            } catch (final IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void setDocument(final int docid) {
                            docId = docid;
                        }
                    };
                }

                @Override
                public boolean needs_score() {
                    return false;
                }
            };
        };
    }
}
