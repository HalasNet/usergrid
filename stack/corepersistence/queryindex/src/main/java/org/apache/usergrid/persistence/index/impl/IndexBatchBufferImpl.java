/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.persistence.index.impl;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.usergrid.persistence.core.metrics.MetricsFactory;
import org.apache.usergrid.persistence.index.IndexBatchBuffer;
import org.apache.usergrid.persistence.index.IndexFig;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.support.replication.ShardReplicationOperationRequestBuilder;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * Classy class class.
 */
@Singleton
public class IndexBatchBufferImpl implements IndexBatchBuffer {

    private static final Logger log = LoggerFactory.getLogger(IndexBatchBufferImpl.class);
    private final Client client;
    private final FailureMonitor failureMonitor;
    private final IndexFig config;
    private final boolean refresh;
    private final int timeout;
    private final int bufferSize;
    private final MetricsFactory metricsFactory;
    private final Timer flushTimer;
    private final Counter indexSizeCounter;
    private Producer producer;
    private Subscription producerObservable;
    private ArrayBlockingQueue blockingQueue;

    @Inject
    public IndexBatchBufferImpl(final IndexFig config, final EsProvider provider, MetricsFactory metricsFactory){
        this.metricsFactory = metricsFactory;
        this.client = provider.getClient();
        this.failureMonitor = new FailureMonitorImpl( config, provider );
        this.config = config;
        this.producer = new Producer();
        this.refresh = config.isForcedRefresh();
        this.timeout = config.getIndexBufferTimeout();
        this.bufferSize = config.getIndexBufferSize();
        this.flushTimer = metricsFactory.getTimer(IndexBatchBuffer.class, "index.buffer.flush");
        this.indexSizeCounter =  metricsFactory.getCounter(IndexBatchBuffer.class, "index.buffer.size");
        blockingQueue = new ArrayBlockingQueue(500);
        init();
    }



    private void init() {
        this.producerObservable = Observable.create(producer)
                .doOnNext(new Action1<RequestBuilderContainer>() {
                    @Override
                    public void call(RequestBuilderContainer container) {
                        try {
                            blockingQueue.offer(container, 2500, TimeUnit.MILLISECONDS);
                        }catch (InterruptedException ie){
                            throw new RuntimeException(ie);
                        }
                    }
                })
                .buffer(timeout, TimeUnit.MILLISECONDS, bufferSize)
                .doOnNext(new Action1<List<RequestBuilderContainer>>() {
                    @Override
                    public void call(List<RequestBuilderContainer> builderContainerList) {
                        flushTimer.time();
                        indexSizeCounter.dec(builderContainerList.size());
                        execute();
                    }
                })
                .subscribe();
    }

    public void put(IndexRequestBuilder builder){
        metricsFactory.getCounter(IndexBatchBuffer.class,"index.buffer.size").inc();
        producer.put(new RequestBuilderContainer(builder));
    }

    public void put(DeleteRequestBuilder builder){
        metricsFactory.getCounter(IndexBatchBuffer.class,"index.buffer.size").inc();
        producer.put(new RequestBuilderContainer(builder));
    }

    public void flushAndRefresh(){
        execute(true);
    }
    public void flush(){
        execute();
    }

    private void execute(){
        execute(this.refresh);
    }

    /**
     * Execute the request, check for errors, then re-init the batch for future use
     */
    private synchronized void execute(boolean refresh ) {
        try {
            BulkRequestBuilder bulkRequest = client.prepareBulk();
            bulkRequest.setRefresh(refresh);
            int count = bufferSize;
            while (blockingQueue.size() > 0 && count-- > 0) {
                RequestBuilderContainer container = (RequestBuilderContainer)blockingQueue.take();
                ShardReplicationOperationRequestBuilder builder = container.getBuilder();
                if (builder instanceof IndexRequestBuilder) {
                    bulkRequest.add((IndexRequestBuilder) builder);
                }
                if (builder instanceof DeleteRequestBuilder) {
                    bulkRequest.add((DeleteRequestBuilder) builder);
                }
            }
            //nothing to do, we haven't added anthing to the index
            if (bulkRequest.numberOfActions() == 0) {
                return;
            }

            final BulkResponse responses;

            try {
                responses = bulkRequest.execute().actionGet();
            } catch (Throwable t) {
                log.error("Unable to communicate with elasticsearch");
                failureMonitor.fail("Unable to execute batch", t);
                throw t;
            }

            failureMonitor.success();

            for (BulkItemResponse response : responses) {
                if (response.isFailed()) {
                    throw new RuntimeException("Unable to index documents.  Errors are :"
                            + response.getFailure().getMessage());
                }
            }
        }catch (InterruptedException ie){
            log.error("Problem taking messages off of queue",ie);
            throw new RuntimeException(ie);
        }
    }

    private static class Producer implements Observable.OnSubscribe<RequestBuilderContainer> {

        private Subscriber<? super RequestBuilderContainer> subscriber;

        @Override
        public void call(Subscriber<? super RequestBuilderContainer> subscriber) {
            this.subscriber = subscriber;
        }

        public void put(RequestBuilderContainer r){
            subscriber.onNext(r);
        }
    }

    private static class RequestBuilderContainer{
        private final ShardReplicationOperationRequestBuilder builder;

        public RequestBuilderContainer(ShardReplicationOperationRequestBuilder builder){
            this.builder = builder;
        }

        public ShardReplicationOperationRequestBuilder getBuilder(){
            return builder;
        }
    }

}
