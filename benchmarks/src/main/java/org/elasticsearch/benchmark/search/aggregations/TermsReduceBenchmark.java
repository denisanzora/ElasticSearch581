/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.benchmark.search.aggregations;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.search.QueryPhaseResultConsumer;
import org.elasticsearch.action.search.SearchPhaseController;
import org.elasticsearch.action.search.SearchProgressListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.lucene.search.TopDocsAndMaxScore;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.AggregationReduceContext;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.MultiBucketConsumerService;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.query.QuerySearchResult;
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

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Warmup(iterations = 5)
@Measurement(iterations = 7)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
public class TermsReduceBenchmark {

    private final TermsAggregationBuilder builder = new TermsAggregationBuilder("terms");

    private final SearchPhaseController controller = new SearchPhaseController((task, req) -> new AggregationReduceContext.Builder() {
        @Override
        public AggregationReduceContext forPartialReduction() {
            return new AggregationReduceContext.ForPartial(null, null, task, TermsReduceBenchmark.this.builder);
        }

        @Override
        public AggregationReduceContext forFinalReduction() {
            MultiBucketConsumerService.MultiBucketConsumer bucketConsumer = new MultiBucketConsumerService.MultiBucketConsumer(
                Integer.MAX_VALUE,
                new NoneCircuitBreakerService().getBreaker(CircuitBreaker.REQUEST)
            );
            return new AggregationReduceContext.ForFinal(null, null, task, TermsReduceBenchmark.this.builder, bucketConsumer, PipelineAggregator.PipelineTree.EMPTY);
        }
    });

    @State(Scope.Benchmark)
    public static class TermsList extends AbstractList<InternalAggregations> {
        @Param("1600172297")
        long seed = 0;

        @Param({ "64", "128", "512" })
        int numShards = 0;

        @Param("100")
        int topNSize = 0;

        @Param({ "1", "10", "100" })
        int cardinalityFactor = 0;

        List<InternalAggregations> aggsList;

        @Setup
        public void setup() {
            aggsList = new ArrayList<>();
            final Random rand = new Random(this.seed);
            final int cardinality = this.cardinalityFactor * this.topNSize;
            final BytesRef[] dict = new BytesRef[cardinality];
            for (int i = 0; i < dict.length; i++) {
                dict[i] = new BytesRef(Long.toString(rand.nextLong()));
            }
            for (int i = 0; i < this.numShards; i++) {
                this.aggsList.add(InternalAggregations.from(Collections.singletonList(this.newTerms(rand, dict, true))));
            }
        }

        private StringTerms newTerms(final Random rand, final BytesRef[] dict, final boolean withNested) {
            final Set<BytesRef> randomTerms = new HashSet<>();
            for (int i = 0; i < this.topNSize; i++) {
                randomTerms.add(dict[rand.nextInt(dict.length)]);
            }
            final List<StringTerms.Bucket> buckets = new ArrayList<>();
            for (final BytesRef term : randomTerms) {
                final InternalAggregations subAggs;
                if (withNested) {
                    subAggs = InternalAggregations.from(Collections.singletonList(this.newTerms(rand, dict, false)));
                } else {
                    subAggs = InternalAggregations.EMPTY;
                }
                buckets.add(new StringTerms.Bucket(term, rand.nextInt(10000), subAggs, true, 0L, DocValueFormat.RAW));
            }

            Collections.sort(buckets, (a, b) -> a.compareKey(b));
            return new StringTerms(
                "terms",
                BucketOrder.key(true),
                BucketOrder.count(false),
                this.topNSize,
                1,
                Collections.emptyMap(),
                DocValueFormat.RAW,
                this.numShards,
                true,
                0,
                buckets,
                null
            );
        }

        @Override
        public InternalAggregations get(final int index) {
            return this.aggsList.get(index);
        }

        @Override
        public int size() {
            return this.aggsList.size();
        }
    }

    @Param({ "32", "512" })
    private int bufferSize = 0;

    @Benchmark
    public SearchPhaseController.ReducedQueryPhase reduceAggs(final TermsList candidateList) throws Exception {
        final List<QuerySearchResult> shards = new ArrayList<>();
        for (int i = 0; i < candidateList.size(); i++) {
            final QuerySearchResult result = new QuerySearchResult();
            result.setShardIndex(i);
            result.from(0);
            result.size(0);
            result.topDocs(
                new TopDocsAndMaxScore(
                    new TopDocs(new TotalHits(1000, TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO), new ScoreDoc[0]),
                    Float.NaN
                ),
                new DocValueFormat[] { DocValueFormat.RAW }
            );
            result.aggregations(candidateList.get(i));
            result.setSearchShardTarget(new SearchShardTarget("node", new ShardId(new Index("index", "index"), i), null));
            shards.add(result);
        }
        final SearchRequest request = new SearchRequest();
        request.source(new SearchSourceBuilder().size(0).aggregation(AggregationBuilders.terms("test")));
        request.setBatchedReduceSize(this.bufferSize);
        final ExecutorService executor = Executors.newFixedThreadPool(1);
        final AtomicBoolean isCanceled = new AtomicBoolean();
        final QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
            request,
            executor,
            new NoopCircuitBreaker(CircuitBreaker.REQUEST),
            this.controller,
            isCanceled::get,
            SearchProgressListener.NOOP,
            shards.size(),
            exc -> {}
        );
        final CountDownLatch latch = new CountDownLatch(shards.size());
        for (int i = 0; i < shards.size(); i++) {
            consumer.consumeResult(shards.get(i), () -> latch.countDown());
        }
        latch.await();
        final SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();
        executor.shutdownNow();
        return phase;
    }
}
