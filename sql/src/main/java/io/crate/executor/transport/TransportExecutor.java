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
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import io.crate.action.job.ContextPreparer;
import io.crate.action.job.ExecutionNodeOperationStarter;
import io.crate.action.sql.DDLStatementDispatcher;
import io.crate.breaker.CrateCircuitBreakerService;
import io.crate.executor.*;
import io.crate.executor.task.DDLTask;
import io.crate.executor.task.NoopTask;
import io.crate.executor.transport.task.*;
import io.crate.executor.transport.task.elasticsearch.*;
import io.crate.jobs.JobContextService;
import io.crate.metadata.Functions;
import io.crate.metadata.ReferenceResolver;
import io.crate.operation.ImplementationSymbolVisitor;
import io.crate.operation.PageDownstreamFactory;
import io.crate.operation.collect.HandlerSideDataCollectOperation;
import io.crate.operation.collect.StatsTables;
import io.crate.operation.projectors.ProjectionToProjectorVisitor;
import io.crate.planner.*;
import io.crate.planner.node.PlanNode;
import io.crate.planner.node.PlanNodeVisitor;
import io.crate.planner.node.StreamerVisitor;
import io.crate.planner.node.ddl.*;
import io.crate.planner.node.dml.*;
import io.crate.planner.node.dql.*;
import org.elasticsearch.action.bulk.BulkRetryCoordinatorPool;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class TransportExecutor implements Executor, TaskExecutor {

    private final Functions functions;
    private final TaskCollectingVisitor planVisitor;
    private Provider<DDLStatementDispatcher> ddlAnalysisDispatcherProvider;
    private final StatsTables statsTables;
    private final NodeVisitor nodeVisitor;
    private final ThreadPool threadPool;

    private final Settings settings;
    private final ClusterService clusterService;
    private final JobContextService jobContextService;
    private final ContextPreparer contextPreparer;
    private final ExecutionNodeOperationStarter executionNodeOperationStarter;
    private final TransportActionProvider transportActionProvider;
    private final BulkRetryCoordinatorPool bulkRetryCoordinatorPool;

    private final ImplementationSymbolVisitor globalImplementationSymbolVisitor;
    private final ProjectionToProjectorVisitor globalProjectionToProjectionVisitor;

    // operation for handler side collecting
    private final HandlerSideDataCollectOperation handlerSideDataCollectOperation;
    private final CircuitBreaker circuitBreaker;

    private final PageDownstreamFactory pageDownstreamFactory;

    private final StreamerVisitor streamerVisitor;

    @Inject
    public TransportExecutor(Settings settings,
                             JobContextService jobContextService,
                             ContextPreparer contextPreparer,
                             ExecutionNodeOperationStarter executionNodeOperationStarter,
                             TransportActionProvider transportActionProvider,
                             ThreadPool threadPool,
                             Functions functions,
                             ReferenceResolver referenceResolver,
                             HandlerSideDataCollectOperation handlerSideDataCollectOperation,
                             PageDownstreamFactory pageDownstreamFactory,
                             Provider<DDLStatementDispatcher> ddlAnalysisDispatcherProvider,
                             StatsTables statsTables,
                             ClusterService clusterService,
                             CrateCircuitBreakerService breakerService,
                             BulkRetryCoordinatorPool bulkRetryCoordinatorPool,
                             StreamerVisitor streamerVisitor) {
        this.settings = settings;
        this.jobContextService = jobContextService;
        this.contextPreparer = contextPreparer;
        this.executionNodeOperationStarter = executionNodeOperationStarter;
        this.transportActionProvider = transportActionProvider;
        this.handlerSideDataCollectOperation = handlerSideDataCollectOperation;
        this.pageDownstreamFactory = pageDownstreamFactory;
        this.threadPool = threadPool;
        this.functions = functions;
        this.ddlAnalysisDispatcherProvider = ddlAnalysisDispatcherProvider;
        this.statsTables = statsTables;
        this.clusterService = clusterService;
        this.bulkRetryCoordinatorPool = bulkRetryCoordinatorPool;
        this.streamerVisitor = streamerVisitor;
        this.nodeVisitor = new NodeVisitor();
        this.planVisitor = new TaskCollectingVisitor();
        this.circuitBreaker = breakerService.getBreaker(CrateCircuitBreakerService.QUERY_BREAKER);
        this.globalImplementationSymbolVisitor = new ImplementationSymbolVisitor(
                referenceResolver, functions, RowGranularity.CLUSTER);
        this.globalProjectionToProjectionVisitor = new ProjectionToProjectorVisitor(
                clusterService,
                threadPool,
                settings,
                transportActionProvider,
                bulkRetryCoordinatorPool,
                globalImplementationSymbolVisitor);
    }

    @Override
    public Job newJob(Plan plan) {
        final Job job = new Job();
        List<Task> tasks = planVisitor.process(plan, job);
        job.addTasks(tasks);
        return job;
    }

    @Override
    public List<ListenableFuture<TaskResult>> execute(Job job) {
        assert job.tasks().size() > 0;
        return execute(job.tasks());

    }

    @Override
    public List<Task> newTasks(PlanNode planNode, UUID jobId) {
        return planNode.accept(nodeVisitor, jobId);
    }

    @Override
    public List<ListenableFuture<TaskResult>> execute(Collection<Task> tasks) {
        Task lastTask = null;
        assert tasks.size() > 0 : "need at least one task to execute";
        for (Task task : tasks) {
            // chaining tasks
            if (lastTask != null) {
                task.upstreamResult(lastTask.result());
            }
            task.start();
            lastTask = task;
        }
        assert lastTask != null;
        return lastTask.result();
    }

    class TaskCollectingVisitor extends PlanVisitor<Job, List<Task>> {

        @Override
        public List<Task> visitIterablePlan(IterablePlan plan, Job job) {
            List<Task> tasks = new ArrayList<>();
            for (PlanNode planNode : plan) {
                tasks.addAll(planNode.accept(nodeVisitor, job.id()));
            }
            return tasks;
        }

        @Override
        public List<Task> visitNoopPlan(NoopPlan plan, Job job) {
            return ImmutableList.<Task>of(NoopTask.INSTANCE);
        }

        @Override
        public List<Task> visitGlobalAggregate(GlobalAggregate plan, Job job) {
            return ImmutableList.of(createExecutableNodesTask(job, plan.collectNode(), plan.mergeNode()));
        }

        @Override
        public List<Task> visitCollectAndMerge(CollectAndMerge plan, Job job) {
            return ImmutableList.of(createExecutableNodesTask(job, plan.collectNode(), plan.localMergeNode()));
        }

        @Override
        public List<Task> visitQueryAndFetch(QueryAndFetch plan, Job job) {
            return ImmutableList.of(createExecutableNodesTask(job, plan.collectNode(), plan.localMergeNode()));
        }

        @Override
        public List<Task> visitCountPlan(CountPlan countPlan, Job job) {
            return ImmutableList.<Task>of(new ExecutionNodesTask(
                    job.id(),
                    clusterService,
                    contextPreparer,
                    executionNodeOperationStarter,
                    handlerSideDataCollectOperation,
                    globalProjectionToProjectionVisitor,
                    jobContextService,
                    pageDownstreamFactory,
                    statsTables,
                    threadPool,
                    transportActionProvider.transportJobInitAction(),
                    transportActionProvider.transportCloseContextNodeAction(),
                    streamerVisitor,
                    circuitBreaker,
                    countPlan.mergeNode(),
                    countPlan.countNode()
            ));
        }

        private Task createExecutableNodesTask(Job job, CollectNode collectNode, @Nullable MergeNode localMergeNode) {
            collectNode.jobId(job.id());
            if (localMergeNode != null) {
                localMergeNode.jobId(job.id());
            }
            return new ExecutionNodesTask(
                    job.id(),
                    clusterService,
                    contextPreparer,
                    executionNodeOperationStarter,
                    handlerSideDataCollectOperation,
                    globalProjectionToProjectionVisitor,
                    jobContextService,
                    pageDownstreamFactory,
                    statsTables,
                    threadPool,
                    transportActionProvider.transportJobInitAction(),
                    transportActionProvider.transportCloseContextNodeAction(),
                    streamerVisitor,
                    circuitBreaker,
                    localMergeNode,
                    collectNode
            );
        }

        @Override
        public List<Task> visitNonDistributedGroupBy(NonDistributedGroupBy plan, Job job) {
            return ImmutableList.of(createExecutableNodesTask(job, plan.collectNode(), plan.localMergeNode()));
        }

        @Override
        public List<Task> visitUpsert(Upsert plan, Job job) {
            ImmutableList.Builder<Task> taskBuilder = ImmutableList.builder();

            for (Plan subPlan : plan.nodes()) {
                List<Task> subTasks = planVisitor.process(subPlan, job);
                UpsertTask upsertTask = new UpsertTask(TransportExecutor.this, job.id(), subTasks);
                taskBuilder.add(upsertTask);
            }

            return taskBuilder.build();
        }

        @Override
        public List<Task> visitDistributedGroupBy(DistributedGroupBy plan, Job job) {
            plan.collectNode().jobId(job.id());
            plan.reducerMergeNode().jobId(job.id());
            MergeNode localMergeNode = plan.localMergeNode();
            if (localMergeNode != null) {
                localMergeNode.jobId(job.id());
            }
            return ImmutableList.<Task>of(
                    new ExecutionNodesTask(
                            job.id(),
                            clusterService,
                            contextPreparer,
                            executionNodeOperationStarter,
                            handlerSideDataCollectOperation,
                            globalProjectionToProjectionVisitor,
                            jobContextService,
                            pageDownstreamFactory,
                            statsTables,
                            threadPool,
                            transportActionProvider.transportJobInitAction(),
                            transportActionProvider.transportCloseContextNodeAction(),
                            streamerVisitor,
                            circuitBreaker,
                            localMergeNode,
                            plan.collectNode(),
                            plan.reducerMergeNode()
                    ));
        }

        @Override
        public List<Task> visitInsertByQuery(InsertFromSubQuery node, Job job) {
            List<Task> tasks = process(node.innerPlan(), job);
            if(node.handlerMergeNode().isPresent()) {
                // TODO: remove this hack
                Task previousTask = Iterables.getLast(tasks);
                if (previousTask instanceof ExecutionNodesTask) {
                    ((ExecutionNodesTask) previousTask).mergeNode(node.handlerMergeNode().get());
                } else {
                    ArrayList<Task> tasks2 = new ArrayList<>(tasks);
                    tasks2.addAll(nodeVisitor.visitMergeNode(node.handlerMergeNode().get(), job.id()));
                    return tasks2;
                }
            }
            return tasks;
        }

        @Override
        public List<Task> visitQueryThenFetch(QueryThenFetch plan, Job job) {
            return ImmutableList.of(createExecutableNodesTask(job, plan.collectNode(), plan.mergeNode()));
        }
    }

    class NodeVisitor extends PlanNodeVisitor<UUID, ImmutableList<Task>> {

        private ImmutableList<Task> singleTask(Task task) {
            return ImmutableList.of(task);
        }

        @Override
        public ImmutableList<Task> visitGenericDDLNode(GenericDDLNode node, UUID jobId) {
            return singleTask(new DDLTask(jobId, ddlAnalysisDispatcherProvider.get(), node));
        }

        @Override
        public ImmutableList<Task> visitESGetNode(ESGetNode node, UUID jobId) {
            return singleTask(new ESGetTask(
                    jobId,
                    functions,
                    globalProjectionToProjectionVisitor,
                    transportActionProvider.transportMultiGetAction(),
                    transportActionProvider.transportGetAction(),
                    node));
        }

        @Override
        public ImmutableList<Task> visitESDeleteByQueryNode(ESDeleteByQueryNode node, UUID jobId) {
            return singleTask(new ESDeleteByQueryTask(
                    jobId,
                    node,
                    transportActionProvider.transportDeleteByQueryAction()));
        }

        @Override
        public ImmutableList<Task> visitESDeleteNode(ESDeleteNode node, UUID jobId) {
            return singleTask(new ESDeleteTask(
                    jobId,
                    node,
                    transportActionProvider.transportDeleteAction()));
        }

        @Override
        public ImmutableList<Task> visitCreateTableNode(CreateTableNode node, UUID jobId) {
            return singleTask(new CreateTableTask(
                            jobId,
                            clusterService,
                            transportActionProvider.transportCreateIndexAction(),
                            transportActionProvider.transportDeleteIndexAction(),
                            transportActionProvider.transportPutIndexTemplateAction(),
                            node)
            );
        }

        @Override
        public ImmutableList<Task> visitESCreateTemplateNode(ESCreateTemplateNode node, UUID jobId) {
            return singleTask(new ESCreateTemplateTask(jobId,
                    node,
                    transportActionProvider.transportPutIndexTemplateAction()));
        }

        @Override
        public ImmutableList<Task> visitSymbolBasedUpsertByIdNode(SymbolBasedUpsertByIdNode node, UUID jobId) {
            return singleTask(new SymbolBasedUpsertByIdTask(jobId,
                    clusterService,
                    settings,
                    transportActionProvider.symbolBasedTransportShardUpsertActionDelegate(),
                    transportActionProvider.transportCreateIndexAction(),
                    transportActionProvider.transportBulkCreateIndicesAction(),
                    bulkRetryCoordinatorPool,
                    node));
        }

        @Override
        public ImmutableList<Task> visitUpsertByIdNode(UpsertByIdNode node, UUID jobId) {
            return singleTask(new UpsertByIdTask(jobId,
                    clusterService,
                    settings,
                    transportActionProvider.transportShardUpsertActionDelegate(),
                    transportActionProvider.transportCreateIndexAction(),
                    transportActionProvider.transportBulkCreateIndicesAction(),
                    bulkRetryCoordinatorPool,
                    node));
        }

        @Override
        public ImmutableList<Task> visitDropTableNode(DropTableNode node, UUID jobId) {
            return singleTask(new DropTableTask(jobId,
                    transportActionProvider.transportDeleteIndexTemplateAction(),
                    transportActionProvider.transportDeleteIndexAction(),
                    node));
        }

        @Override
        public ImmutableList<Task> visitESDeleteIndexNode(ESDeleteIndexNode node, UUID jobId) {
            return singleTask(new ESDeleteIndexTask(jobId,
                    transportActionProvider.transportDeleteIndexAction(),
                    node));
        }

        @Override
        public ImmutableList<Task> visitESClusterUpdateSettingsNode(ESClusterUpdateSettingsNode node, UUID jobId) {
            return singleTask(new ESClusterUpdateSettingsTask(
                    jobId,
                    transportActionProvider.transportClusterUpdateSettingsAction(),
                    node));
        }

        @Override
        protected ImmutableList<Task> visitPlanNode(PlanNode node, UUID jobId) {
            throw new UnsupportedOperationException(
                    String.format("Can't generate job/task for planNode %s", node));
        }
    }
}
