/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.reference.sys.shards;

import io.crate.metadata.MetaDataModule;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.shard.MetaDataShardModule;
import io.crate.metadata.shard.ShardReferenceResolver;
import io.crate.metadata.sys.MetaDataSysModule;
import io.crate.metadata.sys.SysShardsTableInfo;
import io.crate.operation.reference.sys.shard.ShardSizeExpression;
import io.crate.operation.reference.sys.shard.SysShardExpressionModule;
import io.crate.test.integration.CrateUnitTest;
import org.elasticsearch.action.admin.indices.template.put.TransportPutIndexTemplateAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.indexing.IndexingOperationListener;
import org.elasticsearch.index.indexing.ShardIndexingService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.index.store.StoreStats;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.*;

public class ShardSizeExpressionTest extends CrateUnitTest {

    private static final String INDEX_NAME = "wikipedia_de";
    private static final ReferenceIdent IDENT = new ReferenceIdent(SysShardsTableInfo.IDENT, "size");
    private ShardSizeExpression shardSizeExpression;

    private final AtomicLong sizeValue = new AtomicLong(0L);
    private IndexingOperationListener indexingOperationListener;
    ArgumentCaptor<IndexingOperationListener> listenerArgumentCaptor;

    class TestModule extends AbstractModule {

        @SuppressWarnings("unchecked")
        @Override
        protected void configure() {
            bind(Settings.class).toInstance(ImmutableSettings.EMPTY);

            ClusterService clusterService = mock(ClusterService.class);
            bind(ClusterService.class).toInstance(clusterService);

            Index index = new Index(SysShardsTableInfo.IDENT.name());
            bind(Index.class).toInstance(index);

            ShardId shardId = mock(ShardId.class);
            when(shardId.getId()).thenReturn(1);
            when(shardId.getIndex()).thenAnswer(new Answer<String>() {
                @Override
                public String answer(InvocationOnMock invocation) throws Throwable {
                    return INDEX_NAME;
                }
            });
            bind(ShardId.class).toInstance(shardId);
            IndexShard indexShard = mock(IndexShard.class);
            bind(IndexShard.class).toInstance(indexShard);


            when(indexShard.storeStats()).thenAnswer(new Answer<StoreStats>() {
                @Override
                public StoreStats answer(InvocationOnMock invocation) throws Throwable {
                    Long size = sizeValue.getAndIncrement();
                    StoreStats storeStats = mock(StoreStats.class);
                    when(storeStats.getSizeInBytes()).thenReturn(size);
                    return storeStats;
                }
            });
            // capture indexinglistener
            ShardIndexingService shardIndexingService = mock(ShardIndexingService.class);
            listenerArgumentCaptor = ArgumentCaptor.forClass(IndexingOperationListener.class);
            doNothing().when(shardIndexingService).addListener(listenerArgumentCaptor.capture());
            bind(ShardIndexingService.class).toInstance(shardIndexingService);

            TransportPutIndexTemplateAction transportPutIndexTemplateAction = mock(TransportPutIndexTemplateAction.class);
            bind(TransportPutIndexTemplateAction.class).toInstance(transportPutIndexTemplateAction);

            MetaData metaData = mock(MetaData.class);
            when(metaData.concreteAllOpenIndices()).thenReturn(new String[0]);
            when(metaData.templates()).thenReturn(ImmutableOpenMap.<String, IndexTemplateMetaData>of());
            ClusterState clusterState = mock(ClusterState.class);
            when(clusterService.state()).thenReturn(clusterState);
            when(clusterState.metaData()).thenReturn(metaData);
        }
    }

    @Before
    public void prepare() throws Exception {
        Injector injector = new ModulesBuilder().add(
                new TestModule(),
                new MetaDataModule(),
                new MetaDataSysModule(),
                new MetaDataShardModule(),
                new SysShardExpressionModule()
        ).createInjector();
        indexingOperationListener = listenerArgumentCaptor.getValue();
        shardSizeExpression = (ShardSizeExpression) injector.getInstance(ShardReferenceResolver.class).getImplementation(IDENT);
    }

    @Test
    public void testShardSizeNotChangedWithoutIndexingChange() throws Exception {
        assertThat(0L, is(shardSizeExpression.value()));
        assertThat(0L, is(shardSizeExpression.value()));
        indexingOperationListener.postCreate(null);
        assertThat(1L, is(shardSizeExpression.value()));
        assertThat(1L, is(shardSizeExpression.value()));
        indexingOperationListener.postDelete(null);
        assertThat(2L, is(shardSizeExpression.value()));
        assertThat(2L, is(shardSizeExpression.value()));
        indexingOperationListener.postIndex(null);
        assertThat(3L, is(shardSizeExpression.value()));
        assertThat(3L, is(shardSizeExpression.value()));
    }
}
