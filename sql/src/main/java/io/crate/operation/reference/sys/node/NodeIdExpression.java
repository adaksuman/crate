/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed any another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.reference.sys.node;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.discovery.Discovery;


public class NodeIdExpression extends SysNodeExpression<BytesRef> {

    public static final String NAME = "id";

    private final ClusterService clusterService;
    private BytesRef value;

    @Inject
    public NodeIdExpression(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Override
    public BytesRef value() {
        DiscoveryNode localNode = clusterService.localNode();
        // value could not be ready on node start-up, but is static once set
        if (value == null && localNode != null) {
            value = new BytesRef(localNode.getId());
        }
        return value;
    }

}
