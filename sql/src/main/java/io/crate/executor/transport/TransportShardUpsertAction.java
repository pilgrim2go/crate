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

package io.crate.executor.transport;


import io.crate.executor.transport.task.elasticsearch.FieldExtractor;
import io.crate.executor.transport.task.elasticsearch.FieldExtractorFactory;
import io.crate.executor.transport.task.elasticsearch.SymbolToFieldExtractor;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Functions;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.operation.AssignmentSymbolVisitor;
import io.crate.operation.ImplementationSymbolVisitor;
import io.crate.operation.Input;
import io.crate.operation.collect.CollectExpression;
import io.crate.planner.symbol.InputColumn;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import io.crate.planner.symbol.SymbolFormatter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.index.TransportIndexAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportActions;
import org.elasticsearch.action.support.replication.TransportShardReplicationOperationAction;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.operation.plain.Preference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.DocumentAlreadyExistsException;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.engine.DocumentSourceMissingException;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.mapper.internal.RoutingFieldMapper;
import org.elasticsearch.index.mapper.internal.TTLFieldMapper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.fetch.source.FetchSourceContext;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Singleton
public class TransportShardUpsertAction extends TransportShardReplicationOperationAction<ShardUpsertRequest, ShardUpsertRequest, ShardUpsertResponse> {

    private final static String ACTION_NAME = "indices:crate/data/write/upsert";
    private final static SymbolToFieldExtractor SYMBOL_TO_FIELD_EXTRACTOR = new SymbolToFieldExtractor(new GetResultFieldExtractorFactory());

    private final TransportIndexAction indexAction;
    private final IndicesService indicesService;
    private final Functions functions;
    private final AssignmentSymbolVisitor assignmentSymbolVisitor;
    private final SymbolToInputVisitor symbolToInputVisitor;

    @Inject
    public TransportShardUpsertAction(Settings settings,
                                      ThreadPool threadPool,
                                      ClusterService clusterService,
                                      TransportService transportService,
                                      ActionFilters actionFilters,
                                      TransportIndexAction indexAction,
                                      IndicesService indicesService,
                                      ShardStateAction shardStateAction,
                                      Functions functions) {
        super(settings, ACTION_NAME, transportService, clusterService, indicesService, threadPool, shardStateAction, actionFilters);
        this.indexAction = indexAction;
        this.indicesService = indicesService;
        this.functions = functions;
        assignmentSymbolVisitor = new AssignmentSymbolVisitor();
        symbolToInputVisitor = new SymbolToInputVisitor(functions);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.BULK;
    }

    @Override
    protected ShardUpsertRequest newRequestInstance() {
        return new ShardUpsertRequest();
    }

    @Override
    protected ShardUpsertRequest newReplicaRequestInstance() {
        return new ShardUpsertRequest();
    }

    @Override
    protected ShardUpsertResponse newResponseInstance() {
        return new ShardUpsertResponse();
    }

    @Override
    protected boolean resolveIndex() {
        return true;
    }

    @Override
    protected boolean checkWriteConsistency() {
        return false;
    }

    @Override
    protected boolean ignoreReplicas() {
        return true;
    }

    @Override
    protected ShardIterator shards(ClusterState state, InternalRequest request) {
        return clusterService.operationRouting()
                .getShards(state, request.request().index(), request.request().shardId(), Preference.PRIMARY.type());
    }

    @Override
    protected PrimaryResponse<ShardUpsertResponse, ShardUpsertRequest> shardOperationOnPrimary(ClusterState clusterState, PrimaryOperationRequest shardRequest) {
        ShardUpsertResponse shardUpsertResponse = new ShardUpsertResponse();
        ShardUpsertRequest request = shardRequest.request;
        SymbolToFieldExtractorContext extractorContextUpdate = null;
        SymbolToInputContext implContextInsert = null;
        if (request.updateAssignments() != null) {
            AssignmentSymbolVisitor.Context implContextUpdate = assignmentSymbolVisitor.process(request.updateAssignments().values());
            extractorContextUpdate = new SymbolToFieldExtractorContext(
                    functions,
                    request.updateAssignments().size(),
                    implContextUpdate);
        }
        if (request.insertAssignments() != null) {
            implContextInsert = new SymbolToInputContext(request.insertAssignments().size());
            for (Map.Entry<Reference, Symbol> entry : request.insertAssignments().entrySet()) {
                implContextInsert.referenceInputMap.put(entry.getKey(), symbolToInputVisitor.process(entry.getValue(), implContextInsert));
            }
        }

        Iterator<ShardUpsertRequest.Item> it = request.iterator();
        while (it.hasNext()) {
            ShardUpsertRequest.Item item = it.next();
            try {
                indexItem(
                        request,
                        item, shardRequest.shardId,
                        extractorContextUpdate,
                        implContextInsert,
                        request.insertAssignments() != null, // try insert first
                        0);
                shardUpsertResponse.add(item.location(), new ShardUpsertResponse.Response());
            } catch (Throwable t) {
                if (TransportActions.isShardNotAvailableException(t) || !request.continueOnError()) {
                    throw t;
                } else {
                    logger.debug("{} failed to execute update for [{}]/[{}]",
                            t, request.shardId(), request.type(), item.id());
                    shardUpsertResponse.add(item.location(),
                            new ShardUpsertResponse.Failure(
                                    item.id(),
                                    ExceptionsHelper.detailedMessage(t),
                                    (t instanceof VersionConflictEngineException)));
                }
            }
        }
        return new PrimaryResponse<>(shardRequest.request, shardUpsertResponse, null);
    }


    @Override
    protected void shardOperationOnReplica(ReplicaOperationRequest shardRequest) {

    }

    public IndexResponse indexItem(ShardUpsertRequest request,
                                   ShardUpsertRequest.Item item,
                                   ShardId shardId,
                                   SymbolToFieldExtractorContext extractorContextUpdate,
                                   SymbolToInputContext implContextInsert,
                                   boolean tryInsertFirst,
                                   int retryCount) throws ElasticsearchException {

        try {
            IndexRequest indexRequest;
            if (tryInsertFirst) {
                // try insert first without fetching the document
                try {
                    indexRequest = new IndexRequest(prepareInsert(request, item, implContextInsert), request);
                } catch (IOException e) {
                    throw ExceptionsHelper.convertToElastic(e);
                }
            } else {
                indexRequest = new IndexRequest(prepareUpdate(request, item, shardId, extractorContextUpdate), request);
            }
            return indexAction.execute(indexRequest).actionGet();
        } catch (Throwable t) {
            if (t instanceof VersionConflictEngineException
                    && retryCount < item.retryOnConflict()) {
                return indexItem(request, item, shardId, extractorContextUpdate, implContextInsert, false, retryCount + 1);
            } else if (tryInsertFirst && request.updateAssignments() != null
                    && t instanceof DocumentAlreadyExistsException) {
                // insert failed, document already exists, try update
                return indexItem(request, item, shardId, extractorContextUpdate, implContextInsert, false, 0);
            } else {
                throw t;
            }
        }
    }



    /**
     * Prepares an update request by converting it into an index request.
     *
     * TODO: detect a NOOP and return an update response if true
     */
    @SuppressWarnings("unchecked")
    public IndexRequest prepareUpdate(ShardUpsertRequest request,
                                      ShardUpsertRequest.Item item,
                                      ShardId shardId,
                                      SymbolToFieldExtractorContext extractorContextUpdate) throws ElasticsearchException {
        IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
        IndexShard indexShard = indexService.shardSafe(shardId.id());
        final GetResult getResult = indexShard.getService().get(request.type(), item.id(),
                new String[]{RoutingFieldMapper.NAME, ParentFieldMapper.NAME, TTLFieldMapper.NAME},
                true, item.version(), VersionType.INTERNAL, FetchSourceContext.FETCH_SOURCE, false);

        if (!getResult.isExists()) {
            throw new DocumentMissingException(new ShardId(request.index(), request.shardId()), request.type(), item.id());
        }

        if (getResult.internalSourceRef() == null) {
            // no source, we can't do nothing, through a failure...
            throw new DocumentSourceMissingException(new ShardId(request.index(), request.shardId()), request.type(), item.id());
        }

        Tuple<XContentType, Map<String, Object>> sourceAndContent = XContentHelper.convertToMap(getResult.internalSourceRef(), true);
        final Map<String, Object> updatedSourceAsMap;
        final XContentType updateSourceContentType = sourceAndContent.v1();
        String routing = getResult.getFields().containsKey(RoutingFieldMapper.NAME) ? getResult.field(RoutingFieldMapper.NAME).getValue().toString() : null;
        String parent = getResult.getFields().containsKey(ParentFieldMapper.NAME) ? getResult.field(ParentFieldMapper.NAME).getValue().toString() : null;

        updatedSourceAsMap = sourceAndContent.v2();

        // collect inputs
        Set<CollectExpression<?>> collectExpressions = extractorContextUpdate.implContext.collectExpressions();
        for (CollectExpression<?> collectExpression : collectExpressions) {
            collectExpression.setNextRow(item.row());
        }

        // extract references and evaluate assignments
        Map<Reference, FieldExtractor> extractors = new HashMap<>(request.updateAssignments().size());
        for (Map.Entry<Reference, Symbol> entry : request.updateAssignments().entrySet()) {
            extractors.put(entry.getKey(), SYMBOL_TO_FIELD_EXTRACTOR.convert(entry.getValue(), extractorContextUpdate));
        }

        Map<ColumnIdent, Object> mapToUpdate = new HashMap<>(extractors.size());
        for (Map.Entry<Reference, FieldExtractor> entry : extractors.entrySet()) {
            /**
             * NOTE: mapping isn't applied. So if an Insert was done using the ES Rest Endpoint
             * the data might be returned in the wrong format (date as string instead of long)
             */
            mapToUpdate.put(entry.getKey().ident().columnIdent(), entry.getValue().extract(getResult));
        }

        updateSource(updatedSourceAsMap, mapToUpdate);

        final IndexRequest indexRequest = Requests.indexRequest(request.index())
                .type(request.type())
                .id(item.id())
                .routing(routing)
                .parent(parent)
                .source(updatedSourceAsMap, updateSourceContentType)
                .version(getResult.getVersion());
        indexRequest.operationThreaded(false);
        return indexRequest;
    }

    private IndexRequest prepareInsert(ShardUpsertRequest request,
                                       ShardUpsertRequest.Item item,
                                       SymbolToInputContext implContext) throws IOException {
        // collect inputs
        Set<CollectExpression<?>> collectExpressions = implContext.collectExpressions();
        for (CollectExpression<?> collectExpression : collectExpressions) {
            collectExpression.setNextRow(item.row());
        }

        BytesRef rawSource = null;
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (Map.Entry<Reference, Input<?>> entry : implContext.referenceInputMap.entrySet()) {
            ColumnIdent columnIdent = entry.getKey().ident().columnIdent();
            if (columnIdent.equals(DocSysColumns.RAW)) {
                rawSource = (BytesRef)entry.getValue().value();
                break;
            }
            builder.field(columnIdent.fqn(), entry.getValue().value());
        }
        IndexRequest indexRequest = Requests.indexRequest(request.index()).type(request.type()).id(item.id()).routing(request.routing())
                .create(!request.overwriteDuplicates()).operationThreaded(false);
        if (rawSource != null) {
            indexRequest.source(BytesRef.deepCopyOf(rawSource).bytes);
        } else {
            indexRequest.source(builder.bytes(), false);
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Inserting document with id {}, source: {}", item.id(), indexRequest.source().toUtf8());
        }
        return indexRequest;
    }

    /**
     * Overwrite given values on the source. If the value is a map,
     * it will not be merged but overwritten. The keys of the changes map representing a path of
     * the source map tree.
     * If the path doesn't exists, a new tree will be inserted.
     *
     * TODO: detect NOOP
     */
    @SuppressWarnings("unchecked")
    private void updateSource(Map<String, Object> source, Map<ColumnIdent, Object> changes) {
        for (Map.Entry<ColumnIdent, Object> changesEntry : changes.entrySet()) {
            int pathSize = changesEntry.getKey().path().size();
            if (pathSize > 0) {
                // get or create parent hierarchy

                // first most top one
                String currentKey = changesEntry.getKey().name();
                Map<String, Object> sourceElement = (Map<String, Object>)source.get(currentKey);
                if (sourceElement == null) {
                    // insert parent tree element
                    sourceElement = new HashMap<>();
                    source.put(currentKey, sourceElement);
                }

                // second, path elements without last one
                for (int i = 0; i < changesEntry.getKey().path().size()-1; i++) {
                    currentKey = changesEntry.getKey().path().get(i);
                    sourceElement = (Map<String, Object>)sourceElement.get(currentKey);
                    if (sourceElement == null) {
                        // insert parent tree element
                        sourceElement = new HashMap<>();
                        sourceElement.put(currentKey, new HashMap<String, Object>());
                    }
                }

                // finally set value (last path element)
                sourceElement.put(changesEntry.getKey().path().get(pathSize - 1), changesEntry.getValue());
            } else {
                // overwrite or insert the field
                source.put(changesEntry.getKey().name(), changesEntry.getValue());
            }
        }
    }

    static class SymbolToFieldExtractorContext extends SymbolToFieldExtractor.Context {
        private final AssignmentSymbolVisitor.Context implContext;

        public SymbolToFieldExtractorContext(Functions functions, int size, AssignmentSymbolVisitor.Context implContext) {
            super(functions, size);
            this.implContext = implContext;
        }

        @Override
        public Object inputValueFor(InputColumn inputColumn) {
            return implContext.collectExpressionFor(inputColumn).value();
        }

    }


    static class GetResultFieldExtractorFactory implements FieldExtractorFactory<GetResult, SymbolToFieldExtractor.Context> {
        @Override
        public FieldExtractor<GetResult> build(final Reference reference, SymbolToFieldExtractor.Context context) {
            return new FieldExtractor<GetResult>() {
                @Override
                public Object extract(GetResult getResult) {
                    return reference.valueType().value(XContentMapValues.extractValue(
                            reference.info().ident().columnIdent().fqn(), getResult.sourceAsMap()));
                }
            };
        }
    }

    static class SymbolToInputContext extends ImplementationSymbolVisitor.Context {
        public Map<Reference, Input<?>> referenceInputMap;

        public SymbolToInputContext(int inputsSize) {
            referenceInputMap = new HashMap<>(inputsSize);
        }
    }

    static class SymbolToInputVisitor extends ImplementationSymbolVisitor {

        public SymbolToInputVisitor(Functions functions) {
            super(null, functions, null);
        }

        @Override
        public Input<?> visitReference(Reference symbol, Context context) {
            throw new IllegalArgumentException(SymbolFormatter.format("Cannot handle Reference %s", symbol));
        }
    }

}
