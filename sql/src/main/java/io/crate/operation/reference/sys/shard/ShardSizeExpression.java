/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.operation.reference.sys.shard;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.indexing.IndexingOperationListener;
import org.elasticsearch.index.indexing.ShardIndexingService;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.index.store.StoreStats;

import javax.annotation.Nullable;

public class ShardSizeExpression extends SysShardExpression<Long> {

    public static final String NAME = "size";

    private final IndexShard indexShard;

    @Nullable
    private StoreStats storeStats;

    @Inject
    public ShardSizeExpression(IndexShard indexShard, ShardIndexingService shardIndexingService) {
        super(NAME);
        this.indexShard = indexShard;
        shardIndexingService.addListener(new IndexingOperationListener() {

            private void nullStats() {
                synchronized (ShardSizeExpression.this.indexShard) {
                    storeStats = null;
                }
            }

            @Override
            public void postIndex(Engine.Index index) {
                nullStats();
            }

            @Override
            public void postDelete(Engine.Delete delete) {
                nullStats();
            }

            @Override
            public void postCreate(Engine.Create create) {
                nullStats();
            }

            @Override
            public void postDeleteByQuery(Engine.DeleteByQuery deleteByQuery) {
                nullStats();
            }
        });
    }

    @Override
    public Long value() {
        synchronized (indexShard) {
            if (storeStats == null) {
                storeStats = indexShard.storeStats();
            }
            return storeStats.getSizeInBytes();
        }
    }

}
