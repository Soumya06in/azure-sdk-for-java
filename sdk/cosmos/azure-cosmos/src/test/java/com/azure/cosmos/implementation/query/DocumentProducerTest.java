// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.azure.cosmos.implementation.query;

import com.azure.cosmos.BridgeInternal;
import com.azure.cosmos.ConnectionPolicy;
import com.azure.cosmos.CosmosClientException;
import com.azure.cosmos.CosmosError;
import com.azure.cosmos.FeedResponse;
import com.azure.cosmos.implementation.Document;
import com.azure.cosmos.implementation.GlobalEndpointManager;
import com.azure.cosmos.implementation.HttpConstants;
import com.azure.cosmos.implementation.IRetryPolicyFactory;
import com.azure.cosmos.implementation.PartitionKeyRange;
import com.azure.cosmos.implementation.RetryPolicy;
import com.azure.cosmos.implementation.RxDocumentServiceRequest;
import com.azure.cosmos.implementation.caches.RxPartitionKeyRangeCache;
import com.azure.cosmos.implementation.query.orderbyquery.OrderByRowResult;
import com.azure.cosmos.implementation.query.orderbyquery.OrderbyRowComparer;
import com.azure.cosmos.implementation.routing.PartitionKeyRangeIdentity;
import com.azure.cosmos.implementation.routing.Range;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedListMultimap;
import io.reactivex.subscribers.TestSubscriber;
import org.apache.commons.lang3.RandomUtils;
import org.assertj.core.api.Assertions;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

public class DocumentProducerTest {
    private final static Logger logger = LoggerFactory.getLogger(DocumentProducerTest.class);
    private static final long TIMEOUT = 20000;
    private final static String OrderByPayloadFieldName = "payload";
    private final static String OrderByItemsFieldName = "orderByItems";

    private final static String OrderByIntFieldName = "propInt";
    private final static String DocumentPartitionKeyRangeIdFieldName = "_pkrId";
    private final static String DocumentPartitionKeyRangeMinInclusiveFieldName = "_pkrMinInclusive";
    private final static String DocumentPartitionKeyRangeMaxExclusiveFieldName = "_pkrMaxExclusive";

    private final String collectionRid = "myrid";
    private final String collectionLink = "/dbs/mydb/colls/mycol";

    @DataProvider(name = "splitParamProvider")
    public Object[][] splitParamProvider() {
        return new Object[][]{
                // initial continuation token,
                // # pages from parent before split,
                // # pages from left child after split,
                // # pages from right child after split
                {"init-cp", 10, 5, 6}, {null, 10, 5, 6}, {null, 1000, 500, 600}, {"init-cp", 1000, 500, 600}, {"init" +
                "-cp", 0, 10, 12}, {null, 0, 10, 12}, {null, 0, 1, 1}, {null, 10, 1, 1},};
    }

    private IRetryPolicyFactory mockDocumentClientIRetryPolicyFactory() {
        URL url;
        try {
            url = new URL("http://localhost");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        GlobalEndpointManager globalEndpointManager = Mockito.mock(GlobalEndpointManager.class);
        Mockito.doReturn(url).when(globalEndpointManager).resolveServiceEndpoint(Mockito.any(RxDocumentServiceRequest.class));
        doReturn(false).when(globalEndpointManager).isClosed();
        return new RetryPolicy(globalEndpointManager, ConnectionPolicy.getDefaultPolicy());
    }

    @Test(groups = {"unit"}, dataProvider = "splitParamProvider", timeOut = TIMEOUT)
    public void partitionSplit(String initialContinuationToken, int numberOfResultPagesFromParentBeforeSplit,
            int numberOfResultPagesFromLeftChildAfterSplit, int numberOfResultPagesFromRightChildAfterSplit) {
        int initialPageSize = 7;
        int top = -1;

        String parentPartitionId = "1";
        String leftChildPartitionId = "2";
        String rightChildPartitionId = "3";

        List<FeedResponse<Document>> resultFromParentPartition = mockFeedResponses(parentPartitionId,
                                                                                   numberOfResultPagesFromParentBeforeSplit, 3, false);
        List<FeedResponse<Document>> resultFromLeftChildPartition = mockFeedResponses(leftChildPartitionId,
                                                                                      numberOfResultPagesFromLeftChildAfterSplit, 3, true);
        List<FeedResponse<Document>> resultFromRightChildPartition = mockFeedResponses(rightChildPartitionId,
                                                                                       numberOfResultPagesFromRightChildAfterSplit, 3, true);

        // sanity check
        sanityCheckSplitValidation(parentPartitionId, leftChildPartitionId, rightChildPartitionId,
                                   numberOfResultPagesFromParentBeforeSplit,
                                   numberOfResultPagesFromLeftChildAfterSplit,
                                   numberOfResultPagesFromRightChildAfterSplit, resultFromParentPartition,
                                   resultFromLeftChildPartition, resultFromRightChildPartition);

        // setting up behaviour
        RequestExecutor.PartitionAnswer answerFromParentPartition =
                RequestExecutor.PartitionAnswer.just(parentPartitionId, resultFromParentPartition);
        RequestExecutor.PartitionAnswer splitAnswerFromParentPartition =
                RequestExecutor.PartitionAnswer.alwaysPartitionSplit(parentPartitionId);

        RequestExecutor.PartitionAnswer answerFromLeftChildPartition =
                RequestExecutor.PartitionAnswer.just(leftChildPartitionId, resultFromLeftChildPartition);
        RequestExecutor.PartitionAnswer answerFromRightChildPartition =
                RequestExecutor.PartitionAnswer.just(rightChildPartitionId, resultFromRightChildPartition);

        RequestCreator requestCreator = RequestCreator.simpleMock();
        RequestExecutor requestExecutor = RequestExecutor.
                fromPartitionAnswer(ImmutableList.of(answerFromParentPartition, splitAnswerFromParentPartition,
                                                     answerFromLeftChildPartition, answerFromRightChildPartition));

        PartitionKeyRange parentPartitionKeyRange = mockPartitionKeyRange(parentPartitionId);
        PartitionKeyRange leftChildPartitionKeyRange = mockPartitionKeyRange(leftChildPartitionId);
        PartitionKeyRange rightChildPartitionKeyRange = mockPartitionKeyRange(rightChildPartitionId);

        // this returns replacement ranges upon split detection
        IDocumentQueryClient queryClient = mockQueryClient(ImmutableList.of(leftChildPartitionKeyRange,
                                                                            rightChildPartitionKeyRange));

        DocumentProducer<Document> documentProducer = new DocumentProducer<>(
                queryClient,
                collectionRid,
                null,
                requestCreator,
                requestExecutor,
                parentPartitionKeyRange,
                collectionLink,
                () -> mockDocumentClientIRetryPolicyFactory().getRequestPolicy(),
                Document.class,
                null,
                initialPageSize,
                initialContinuationToken,
                top);

        TestSubscriber<DocumentProducer<Document>.DocumentProducerFeedResponse> subscriber = new TestSubscriber<>();

        documentProducer.produceAsync().subscribe(subscriber);
        subscriber.awaitTerminalEvent();

        subscriber.assertNoErrors();
        subscriber.assertComplete();

        validateSplitCaptureRequests(
                requestCreator.invocations,
                initialContinuationToken,
                parentPartitionId,
                leftChildPartitionId,
                rightChildPartitionId,
                resultFromParentPartition,
                resultFromLeftChildPartition,
                resultFromRightChildPartition);

        // page size match
        assertThat(requestCreator.invocations.stream().map(i -> i.maxItemCount)
                .distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singleton(initialPageSize));

        // expected results
        validateSplitResults(subscriber.values(),
                             parentPartitionId, leftChildPartitionId,
                             rightChildPartitionId, resultFromParentPartition, resultFromLeftChildPartition,
                             resultFromRightChildPartition, false);

        Mockito.verify(queryClient, times(1)).getPartitionKeyRangeCache();
    }

    @Test(groups = {"unit"}, dataProvider = "splitParamProvider", timeOut = TIMEOUT)
    public void orderByPartitionSplit(String initialContinuationToken, int numberOfResultPagesFromParentBeforeSplit,
            int numberOfResultPagesFromLeftChildAfterSplit, int numberOfResultPagesFromRightChildAfterSplit) {
        int initialPageSize = 7;
        int top = -1;

        String parentPartitionId = "1";
        String leftChildPartitionId = "2";
        String rightChildPartitionId = "3";

        Integer initialPropVal = 1;
        List<FeedResponse<Document>> resultFromParentPartition = mockFeedResponses(parentPartitionId,
                                                                                   numberOfResultPagesFromParentBeforeSplit, 3, initialPropVal, false);
        Integer highestValInParentPage = getLastValueInAsc(initialPropVal, resultFromParentPartition);

        List<FeedResponse<Document>> resultFromLeftChildPartition = mockFeedResponses(leftChildPartitionId,
                                                                                      numberOfResultPagesFromLeftChildAfterSplit, 3, highestValInParentPage, true);

        List<FeedResponse<Document>> resultFromRightChildPartition = mockFeedResponses(rightChildPartitionId,
                                                                                       numberOfResultPagesFromRightChildAfterSplit, 3, highestValInParentPage, true);

        // sanity check
        sanityCheckSplitValidation(parentPartitionId, leftChildPartitionId, rightChildPartitionId,
                                   numberOfResultPagesFromParentBeforeSplit,
                                   numberOfResultPagesFromLeftChildAfterSplit,
                                   numberOfResultPagesFromRightChildAfterSplit, resultFromParentPartition,
                                   resultFromLeftChildPartition, resultFromRightChildPartition);

        // setting up behaviour
        RequestExecutor.PartitionAnswer answerFromParentPartition =
                RequestExecutor.PartitionAnswer.just(parentPartitionId, resultFromParentPartition);
        RequestExecutor.PartitionAnswer splitAnswerFromParentPartition =
                RequestExecutor.PartitionAnswer.alwaysPartitionSplit(parentPartitionId);

        RequestExecutor.PartitionAnswer answerFromLeftChildPartition =
                RequestExecutor.PartitionAnswer.just(leftChildPartitionId, resultFromLeftChildPartition);
        RequestExecutor.PartitionAnswer answerFromRightChildPartition =
                RequestExecutor.PartitionAnswer.just(rightChildPartitionId, resultFromRightChildPartition);

        RequestCreator requestCreator = RequestCreator.simpleMock();
        RequestExecutor requestExecutor = RequestExecutor.
                fromPartitionAnswer(ImmutableList.of(answerFromParentPartition, splitAnswerFromParentPartition,
                                                     answerFromLeftChildPartition, answerFromRightChildPartition));

        PartitionKeyRange parentPartitionKeyRange = mockPartitionKeyRange(parentPartitionId);
        PartitionKeyRange leftChildPartitionKeyRange = mockPartitionKeyRange(leftChildPartitionId);
        PartitionKeyRange rightChildPartitionKeyRange = mockPartitionKeyRange(rightChildPartitionId);

        // this returns replacement ranges upon split detection
        IDocumentQueryClient queryCl = mockQueryClient(ImmutableList.of(leftChildPartitionKeyRange,
                                                                        rightChildPartitionKeyRange));

        OrderByDocumentProducer<Document> documentProducer =
                new OrderByDocumentProducer<>(new OrderbyRowComparer<>(ImmutableList.of(SortOrder.Ascending)),
                                              queryCl, collectionRid, null, requestCreator, requestExecutor,
                                              parentPartitionKeyRange, collectionLink, null, Document.class, null,
                                              initialPageSize, initialContinuationToken, top,
                /*targetRangeToOrderByContinuationTokenMap*/new HashMap<>());

        TestSubscriber<DocumentProducer<Document>.DocumentProducerFeedResponse> subscriber = new TestSubscriber<>();

        documentProducer.produceAsync().subscribe(subscriber);
        subscriber.awaitTerminalEvent();

        subscriber.assertNoErrors();
        subscriber.assertComplete();

        validateSplitCaptureRequests(requestCreator.invocations, initialContinuationToken, parentPartitionId,
                                     leftChildPartitionId, rightChildPartitionId, resultFromParentPartition,
                                     resultFromLeftChildPartition, resultFromRightChildPartition);

        // page size match
        assertThat(requestCreator.invocations.stream().map(i -> i.maxItemCount).distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singleton(initialPageSize));

        // expected results
        validateSplitResults(subscriber.values(),
                             parentPartitionId, leftChildPartitionId,
                             rightChildPartitionId, resultFromParentPartition, resultFromLeftChildPartition,
                             resultFromRightChildPartition, true);

        Mockito.verify(queryCl, times(1)).getPartitionKeyRangeCache();
    }

    @Test(groups = {"unit"}, timeOut = TIMEOUT)
    public void simple() {
        int initialPageSize = 7;
        int top = -1;

        String partitionId = "1";

        List<RxDocumentServiceRequest> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(mockRequest(partitionId));
        }

        List<FeedResponse<Document>> responses = mockFeedResponses(partitionId, 10, 3, true);

        RequestCreator requestCreator = RequestCreator.give(requests);
        RequestExecutor requestExecutor = RequestExecutor.fromPartitionAnswer(RequestExecutor.PartitionAnswer.just("1"
                , responses));

        PartitionKeyRange targetRange = mockPartitionKeyRange(partitionId);

        IDocumentQueryClient queryClient = Mockito.mock(IDocumentQueryClient.class);
        String initialContinuationToken = "initial-cp";
        DocumentProducer<Document> documentProducer = new DocumentProducer<>(queryClient, collectionRid, null,
                                                                             requestCreator, requestExecutor,
                                                                             targetRange, collectionLink,
                                                                             () -> mockDocumentClientIRetryPolicyFactory().getRequestPolicy(), Document.class, null, initialPageSize, initialContinuationToken, top);

        TestSubscriber<DocumentProducer.DocumentProducerFeedResponse> subscriber = new TestSubscriber<>();

        documentProducer.produceAsync().subscribe(subscriber);
        subscriber.awaitTerminalEvent();

        subscriber.assertNoErrors();
        subscriber.assertComplete();

        subscriber.assertValueCount(responses.size());

        // requests match
        assertThat(requestCreator.invocations.stream().map(i -> i.invocationResult).collect(Collectors.toList())).containsExactlyElementsOf(requests);

        // requested max page size match
        assertThat(requestCreator.invocations.stream().map(i -> i.maxItemCount).distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singleton(7));

        // continuation tokens
        assertThat(requestCreator.invocations.get(0).continuationToken).isEqualTo(initialContinuationToken);
        assertThat(requestCreator.invocations.stream().skip(1).map(i -> i.continuationToken).collect(Collectors.toList())).containsExactlyElementsOf(responses.stream().limit(9).map(r -> r.getContinuationToken()).collect(Collectors.toList()));

        // source partition
        assertThat(requestCreator.invocations.stream().map(i -> i.sourcePartition).distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singletonList(targetRange));
    }

    @Test(groups = {"unit"}, timeOut = TIMEOUT)
    public void retries() {
        int initialPageSize = 7;
        int top = -1;

        String partitionKeyRangeId = "1";

        RequestCreator requestCreator = RequestCreator.simpleMock();

        List<FeedResponse<Document>> responsesBeforeThrottle = mockFeedResponses(partitionKeyRangeId, 2, 1, false);
        Exception throttlingException = mockThrottlingException(10);
        List<FeedResponse<Document>> responsesAfterThrottle = mockFeedResponses(partitionKeyRangeId, 5, 1, true);

        RequestExecutor.PartitionAnswer behaviourBeforeException =
                RequestExecutor.PartitionAnswer.just(partitionKeyRangeId, responsesBeforeThrottle);
        RequestExecutor.PartitionAnswer exceptionBehaviour =
                RequestExecutor.PartitionAnswer.errors(partitionKeyRangeId,
                                                       Collections.singletonList(throttlingException));
        RequestExecutor.PartitionAnswer behaviourAfterException =
                RequestExecutor.PartitionAnswer.just(partitionKeyRangeId, responsesAfterThrottle);

        RequestExecutor requestExecutor = RequestExecutor.fromPartitionAnswer(behaviourBeforeException,
                                                                              exceptionBehaviour,
                                                                              behaviourAfterException);

        PartitionKeyRange targetRange = mockPartitionKeyRange(partitionKeyRangeId);

        IDocumentQueryClient queryClient = Mockito.mock(IDocumentQueryClient.class);
        String initialContinuationToken = "initial-cp";
        DocumentProducer<Document> documentProducer = new DocumentProducer<>(queryClient, collectionRid, null,
                                                                             requestCreator, requestExecutor,
                                                                             targetRange, collectionLink,
                                                                             () -> mockDocumentClientIRetryPolicyFactory().getRequestPolicy(), Document.class, null, initialPageSize, initialContinuationToken, top);

        TestSubscriber<DocumentProducer.DocumentProducerFeedResponse> subscriber = new TestSubscriber<>();

        documentProducer.produceAsync().subscribe(subscriber);
        subscriber.awaitTerminalEvent();

        subscriber.assertNoErrors();
        subscriber.assertComplete();

        subscriber.assertValueCount(responsesBeforeThrottle.size() + responsesAfterThrottle.size());

        // requested max page size match
        assertThat(requestCreator.invocations.stream().map(i -> i.maxItemCount).distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singleton(7));

        // continuation tokens
        assertThat(requestCreator.invocations.get(0).continuationToken).isEqualTo(initialContinuationToken);

        // source partition
        assertThat(requestCreator.invocations.stream().map(i -> i.sourcePartition).distinct().collect(Collectors.toList())).containsExactlyElementsOf(Collections.singletonList(targetRange));

        List<String> resultContinuationToken =
                subscriber.values().stream().map(r -> r.pageResult.getContinuationToken()).collect(Collectors.toList());
        List<String> beforeExceptionContinuationTokens =
                responsesBeforeThrottle.stream().map(FeedResponse::getContinuationToken).collect(Collectors.toList());
        List<String> afterExceptionContinuationTokens =
                responsesAfterThrottle.stream().map(FeedResponse::getContinuationToken).collect(Collectors.toList());

        assertThat(resultContinuationToken).containsExactlyElementsOf(Iterables.concat(beforeExceptionContinuationTokens, afterExceptionContinuationTokens));

        String continuationTokenOnException = Iterables.getLast(beforeExceptionContinuationTokens);

        assertThat(requestCreator.invocations.stream().map(cr -> cr.continuationToken)).containsExactlyElementsOf(Iterables.concat(ImmutableList.of(initialContinuationToken), Iterables.limit(resultContinuationToken, resultContinuationToken.size() - 1)));

        assertThat(requestExecutor.partitionKeyRangeIdToCapturedInvocation.get(partitionKeyRangeId).stream().map(cr -> cr.request.getContinuation())).containsExactlyElementsOf(Iterables.concat(ImmutableList.of(initialContinuationToken), beforeExceptionContinuationTokens, Collections.singletonList(continuationTokenOnException), Iterables.limit(afterExceptionContinuationTokens, afterExceptionContinuationTokens.size() - 1)));
    }

    @Test(groups = {"unit"}, timeOut = TIMEOUT)
    public void retriesExhausted() {
        int initialPageSize = 7;
        int top = -1;

        String partitionKeyRangeId = "1";

        RequestCreator requestCreator = RequestCreator.simpleMock();

        List<FeedResponse<Document>> responsesBeforeThrottle = mockFeedResponses(partitionKeyRangeId, 1, 1, false);
        Exception throttlingException = mockThrottlingException(10);

        RequestExecutor.PartitionAnswer behaviourBeforeException =
                RequestExecutor.PartitionAnswer.just(partitionKeyRangeId, responsesBeforeThrottle);
        RequestExecutor.PartitionAnswer exceptionBehaviour =
                RequestExecutor.PartitionAnswer.errors(partitionKeyRangeId, Collections.nCopies(10,
                                                                                                throttlingException));

        RequestExecutor requestExecutor = RequestExecutor.fromPartitionAnswer(behaviourBeforeException,
                                                                              exceptionBehaviour);

        PartitionKeyRange targetRange = mockPartitionKeyRange(partitionKeyRangeId);

        IDocumentQueryClient queryClient = Mockito.mock(IDocumentQueryClient.class);
        String initialContinuationToken = "initial-cp";
        DocumentProducer<Document> documentProducer = new DocumentProducer<Document>(queryClient, collectionRid, null,
                                                                             requestCreator, requestExecutor,
                                                                             targetRange, collectionRid,
                                                                             () -> mockDocumentClientIRetryPolicyFactory().getRequestPolicy(), Document.class, null, initialPageSize, initialContinuationToken, top);

        TestSubscriber<DocumentProducer.DocumentProducerFeedResponse> subscriber = new TestSubscriber<>();

        documentProducer.produceAsync().subscribe(subscriber);
        subscriber.awaitTerminalEvent();

        subscriber.assertError(throttlingException);
        subscriber.assertValueCount(responsesBeforeThrottle.size());
    }

    private CosmosClientException mockThrottlingException(long retriesAfter) {
        CosmosClientException throttleException = mock(CosmosClientException.class);
        doReturn(429).when(throttleException).getStatusCode();
        doReturn(retriesAfter).when(throttleException).getRetryAfterInMilliseconds();
        return throttleException;
    }

    private List<FeedResponse<Document>> mockFeedResponses(String partitionKeyRangeId, int numberOfPages,
            int numberOfDocsPerPage, boolean completed) {
        return mockFeedResponsesPartiallySorted(partitionKeyRangeId, numberOfPages, numberOfDocsPerPage, false, -1,
                                                completed);
    }

    private List<FeedResponse<Document>> mockFeedResponses(String partitionKeyRangeId, int numberOfPages,
            int numberOfDocsPerPage, int orderByFieldInitialVal, boolean completed) {
        return mockFeedResponsesPartiallySorted(partitionKeyRangeId, numberOfPages, numberOfDocsPerPage, true,
                                                orderByFieldInitialVal, completed);
    }

    private List<FeedResponse<Document>> mockFeedResponsesPartiallySorted(String partitionKeyRangeId,
            int numberOfPages, int numberOfDocsPerPage, boolean isOrderby, int orderByFieldInitialVal,
            boolean completed) {
        String uuid = UUID.randomUUID().toString();
        List<FeedResponse<Document>> responses = new ArrayList<>();
        for (int i = 0; i < numberOfPages; i++) {
            FeedResponseBuilder<Document> rfb = FeedResponseBuilder.queryFeedResponseBuilder(Document.class);
            List<Document> res = new ArrayList<>();

            for (int j = 0; j < numberOfDocsPerPage; j++) {

                Document d = getDocumentDefinition();
                if (isOrderby) {
                    BridgeInternal.setProperty(d, OrderByIntFieldName, orderByFieldInitialVal + RandomUtils.nextInt(0, 3));
                    BridgeInternal.setProperty(d, DocumentPartitionKeyRangeIdFieldName, partitionKeyRangeId);
                    PartitionKeyRange pkr = mockPartitionKeyRange(partitionKeyRangeId);

                    BridgeInternal.setProperty(d, DocumentPartitionKeyRangeMinInclusiveFieldName, pkr.getMinInclusive());
                    BridgeInternal.setProperty(d, DocumentPartitionKeyRangeMaxExclusiveFieldName, pkr.getMaxExclusive());

                    QueryItem qi = new QueryItem("{ \"item\": " + Integer.toString(d.getInt(OrderByIntFieldName)) +
                                                         " }");
                    String json =
                            "{\"" + OrderByPayloadFieldName + "\" : " + d.toJson() + ", \"" + OrderByItemsFieldName + "\" : [ " + qi.toJson() + " ] }";

                    OrderByRowResult<Document> row = new OrderByRowResult<>(Document.class, json,
                                                                            mockPartitionKeyRange(partitionKeyRangeId), "backend continuation token");
                    res.add(row);
                } else {
                    res.add(d);
                }
            }
            rfb.withResults(res);

            if (!(completed && i == numberOfPages - 1)) {
                rfb.withContinuationToken("cp:" + uuid + ":" + i);
            }

            FeedResponse resp = rfb.build();
            responses.add(resp);
        }
        return responses;
    }

    private int getLastValueInAsc(int initialValue, List<FeedResponse<Document>> responsesList) {
        Integer value = null;
        for (FeedResponse<Document> page : responsesList) {
            for (Document d : page.getResults()) {
                Integer tmp = d.getInt(OrderByIntFieldName);
                if (tmp != null) {
                    value = tmp;
                }
            }
        }
        if (value != null) {
            return value;
        } else {
            return initialValue;
        }
    }

    private IDocumentQueryClient mockQueryClient(List<PartitionKeyRange> replacementRanges) {
        IDocumentQueryClient client = Mockito.mock(IDocumentQueryClient.class);
        RxPartitionKeyRangeCache cache = Mockito.mock(RxPartitionKeyRangeCache.class);
        doReturn(cache).when(client).getPartitionKeyRangeCache();
        doReturn(Mono.just(replacementRanges)).when(cache).
                tryGetOverlappingRangesAsync(anyString(), any(Range.class), anyBoolean(), Matchers.anyMap());
        return client;
    }

    private PartitionKeyRange mockPartitionKeyRange(String partitionKeyRangeId) {
        PartitionKeyRange pkr = Mockito.mock(PartitionKeyRange.class);
        doReturn(partitionKeyRangeId).when(pkr).getId();
        doReturn(partitionKeyRangeId + ":AA").when(pkr).getMinInclusive();
        doReturn(partitionKeyRangeId + ":FF").when(pkr).getMaxExclusive();
        return pkr;
    }

    private RxDocumentServiceRequest mockRequest(String partitionKeyRangeId) {
        RxDocumentServiceRequest req = Mockito.mock(RxDocumentServiceRequest.class);
        PartitionKeyRangeIdentity pkri = new PartitionKeyRangeIdentity(partitionKeyRangeId);
        doReturn(pkri).when(req).getPartitionKeyRangeIdentity();
        return req;
    }

    private static void validateSplitCaptureRequests(List<RequestCreator.CapturedInvocation> capturedInvocationList,
            String initialContinuationToken, String parentPartitionId, String leftChildPartitionId,
            String rightChildPartitionId,
            List<FeedResponse<Document>> expectedResultPagesFromParentPartitionBeforeSplit,
            List<FeedResponse<Document>> expectedResultPagesFromLeftChildPartition,
            List<FeedResponse<Document>> expectedResultPagesFromRightChildPartition) {

        int numberOfResultPagesFromParentBeforeSplit = expectedResultPagesFromParentPartitionBeforeSplit.size();
        int numberOfResultPagesFromLeftChildAfterSplit = expectedResultPagesFromLeftChildPartition.size();
        int numberOfResultPagesFromRightChildAfterSplit = expectedResultPagesFromRightChildPartition.size();

        // numberOfResultPagesFromParentBeforeSplit + 1 requests to parent partition
        assertThat(capturedInvocationList.stream().limit(numberOfResultPagesFromParentBeforeSplit + 1).filter(i -> i.sourcePartition.getId().equals(parentPartitionId))).hasSize(numberOfResultPagesFromParentBeforeSplit + 1);

        assertThat(capturedInvocationList.stream().skip(numberOfResultPagesFromParentBeforeSplit + 1).filter(i -> i.sourcePartition.getId().equals(leftChildPartitionId))).hasSize(numberOfResultPagesFromLeftChildAfterSplit);

        assertThat(capturedInvocationList.stream().skip(numberOfResultPagesFromParentBeforeSplit + 1).filter(i -> i.sourcePartition.getId().equals(rightChildPartitionId))).hasSize(numberOfResultPagesFromRightChildAfterSplit);

        BiFunction<Stream<RequestCreator.CapturedInvocation>, String, Stream<RequestCreator.CapturedInvocation>> filterByPartition = (stream, partitionId) -> stream.filter(i -> i.sourcePartition.getId().equals(partitionId));

        Function<List<FeedResponse<Document>>, Stream<String>> extractContinuationToken =
                (list) -> list.stream().map(p -> p.getContinuationToken());

        assertThat(filterByPartition.apply(capturedInvocationList.stream(), parentPartitionId).map(r -> r.continuationToken)).containsExactlyElementsOf(toList(Stream.concat(Stream.of(initialContinuationToken), extractContinuationToken.apply(expectedResultPagesFromParentPartitionBeforeSplit))));

        String expectedInitialChildContinuationTokenInheritedFromParent =
                expectedResultPagesFromParentPartitionBeforeSplit.size() > 0 ?
                        expectedResultPagesFromParentPartitionBeforeSplit.get(expectedResultPagesFromParentPartitionBeforeSplit.size() - 1).getContinuationToken() : initialContinuationToken;

        assertThat(filterByPartition.andThen(s -> s.map(r -> r.continuationToken)).apply(capturedInvocationList.stream(), leftChildPartitionId)).containsExactlyElementsOf(toList(Stream.concat(Stream.of(expectedInitialChildContinuationTokenInheritedFromParent), extractContinuationToken.apply(expectedResultPagesFromLeftChildPartition)
                //drop last page with null cp which doesn't trigger any request
                .limit(expectedResultPagesFromLeftChildPartition.size() - 1))));

        assertThat(filterByPartition.andThen(s -> s.map(r -> r.continuationToken)).apply(capturedInvocationList.stream(), rightChildPartitionId)).containsExactlyElementsOf(toList(Stream.concat(Stream.of(expectedInitialChildContinuationTokenInheritedFromParent), extractContinuationToken.apply(expectedResultPagesFromRightChildPartition)
                //drop last page with null cp which doesn't trigger any request
                .limit(expectedResultPagesFromRightChildPartition.size() - 1))));
    }

    private static void sanityCheckSplitValidation(String parentPartitionId, String leftChildPartitionId,
            String rightChildPartitionId, int numberOfResultPagesFromParentBeforeSplit,
            int numberOfResultPagesFromLeftChildAfterSplit, int numberOfResultPagesFromRightChildAfterSplit,
            List<FeedResponse<Document>> resultFromParent, List<FeedResponse<Document>> resultFromLeftChild,
            List<FeedResponse<Document>> resultFromRightChild) {
        // test sanity check
        assertThat(resultFromParent).hasSize(numberOfResultPagesFromParentBeforeSplit);
        assertThat(resultFromLeftChild).hasSize(numberOfResultPagesFromLeftChildAfterSplit);
        assertThat(resultFromRightChild).hasSize(numberOfResultPagesFromRightChildAfterSplit);

        //validate expected result continuation token
        assertThat(toList(resultFromParent.stream().map(p -> p.getContinuationToken()).filter(cp -> Strings.isNullOrEmpty(cp)))).isEmpty();

        assertThat(toList(resultFromLeftChild.stream().map(p -> p.getContinuationToken()).limit(resultFromLeftChild.size() - 1).filter(cp -> Strings.isNullOrEmpty(cp)))).isEmpty();

        assertThat(resultFromLeftChild.get(resultFromLeftChild.size() - 1).getContinuationToken()).isNullOrEmpty();

        assertThat(toList(resultFromRightChild.stream().map(p -> p.getContinuationToken()).limit(resultFromRightChild.size() - 1).filter(cp -> Strings.isNullOrEmpty(cp)))).isEmpty();

        assertThat(resultFromRightChild.get(resultFromRightChild.size() - 1).getContinuationToken()).isNullOrEmpty();
    }

    private void validateSplitResults(List<DocumentProducer<Document>.DocumentProducerFeedResponse> actualPages,
            String parentPartitionId, String leftChildPartitionId, String rightChildPartitionId,
            List<FeedResponse<Document>> resultFromParent, List<FeedResponse<Document>> resultFromLeftChild,
            List<FeedResponse<Document>> resultFromRightChild, boolean isOrderby) {

        if (isOrderby) {
            Supplier<Stream<Document>> getStreamOfActualDocuments =
                    () -> actualPages.stream().flatMap(p -> p.pageResult.getResults().stream());

            Comparator<? super Document> comparator = new Comparator<Document>() {
                @Override
                public int compare(Document o1, Document o2) {
                    ObjectNode obj1 = (ObjectNode) o1.get(OrderByPayloadFieldName);
                    ObjectNode obj2 = (ObjectNode) o1.get(OrderByPayloadFieldName);

                    int cmp = (obj1).get(OrderByIntFieldName).asInt() - (obj2).get(OrderByIntFieldName).asInt();
                    if (cmp != 0) {
                        return cmp;
                    }

                    return obj1.get(DocumentPartitionKeyRangeMinInclusiveFieldName).asText().compareTo(obj2.get(DocumentPartitionKeyRangeMinInclusiveFieldName).asText());
                }
            };

            List<Document> expectedDocuments = Stream.concat(Stream.concat(resultFromParent.stream(),
                                                                           resultFromLeftChild.stream()),
                                                             resultFromRightChild.stream()).flatMap(p -> p.getResults().stream()).sorted(comparator).collect(Collectors.toList());

            List<String> actualDocuments =
                    getStreamOfActualDocuments.get().map(d -> d.getId()).collect(Collectors.toList());
            assertThat(actualDocuments).containsExactlyElementsOf(expectedDocuments.stream().map(d -> d.getId()).collect(Collectors.toList()));

        } else {
            Assertions.assertThat(actualPages).hasSize(resultFromParent.size() + resultFromLeftChild.size() + resultFromRightChild.size());

            BiFunction<String, Integer, Stream<String>> repeater = (v, cnt) -> {
                return IntStream.range(0, cnt).mapToObj(i -> v);
            };

            List<String> expectedCapturedPartitionIds =
                    toList(Stream.concat(Stream.concat(repeater.apply(parentPartitionId, resultFromParent.size()),
                                                       repeater.apply(leftChildPartitionId,
                                                                      resultFromLeftChild.size())),
                                         repeater.apply(rightChildPartitionId, resultFromRightChild.size())));

            assertThat(toList(partitionKeyRangeIds(actualPages).stream())).containsExactlyInAnyOrderElementsOf(expectedCapturedPartitionIds);

            validateResults(feedResponses(actualPages), ImmutableList.of(resultFromParent, resultFromLeftChild,
                                                                         resultFromRightChild));
        }
    }

    private static <T> List<T> repeat(T t, int cnt) {
        return IntStream.range(0, cnt).mapToObj(i -> t).collect(Collectors.toList());
    }

    private static List<FeedResponse<Document>> feedResponses(List<DocumentProducer<Document>.DocumentProducerFeedResponse> responses) {
        return responses.stream().map(dpFR -> dpFR.pageResult).collect(Collectors.toList());
    }

    private static <T> List<T> toList(Stream<T> stream) {
        return stream.collect(Collectors.toList());
    }

    private static List<String> partitionKeyRangeIds(List<DocumentProducer<Document>.DocumentProducerFeedResponse> responses) {
        return responses.stream().map(dpFR -> dpFR.sourcePartitionKeyRange.getId()).collect(Collectors.toList());
    }

    private static void validateResults(List<FeedResponse<Document>> captured,
            List<List<FeedResponse<Document>>> expectedResponsesFromPartitions) {
        List<FeedResponse<Document>> expected =
                expectedResponsesFromPartitions.stream().flatMap(l -> l.stream()).collect(Collectors.toList());
        assertThat(captured).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            FeedResponse<Document> actualPage = captured.get(i);
            FeedResponse<Document> expectedPage = expected.get(i);
            assertEqual(actualPage, expectedPage);
        }
    }

    private static void assertEqual(FeedResponse<Document> actualPage, FeedResponse<Document> expectedPage) {
        assertThat(actualPage.getResults()).hasSameSizeAs(actualPage.getResults());
        assertThat(actualPage.getContinuationToken()).isEqualTo(expectedPage.getContinuationToken());

        for (int i = 0; i < actualPage.getResults().size(); i++) {
            Document actualDoc = actualPage.getResults().get(i);
            Document expectedDoc = expectedPage.getResults().get(i);
            assertThat(actualDoc.getId()).isEqualTo(expectedDoc.getId());
            assertThat(actualDoc.getString("prop")).isEqualTo(expectedDoc.getString("prop"));
        }
    }

    static abstract class RequestExecutor implements Function<RxDocumentServiceRequest, Flux<FeedResponse<Document>>> {

        LinkedListMultimap<String, CapturedInvocation> partitionKeyRangeIdToCapturedInvocation =
                LinkedListMultimap.create();

        class CapturedInvocation {
            long time = System.nanoTime();
            RxDocumentServiceRequest request;
            FeedResponse invocationResult;
            Exception failureResult;

            public CapturedInvocation(RxDocumentServiceRequest request, Exception ex) {
                this.request = request;
                this.failureResult = ex;
            }

            public CapturedInvocation(RxDocumentServiceRequest request, PartitionAnswer.Response resp) {
                this.request = request;
                this.invocationResult = resp.invocationResult;
                this.failureResult = resp.failureResult;
            }
        }

        private static CosmosClientException partitionKeyRangeGoneException() {
            Map<String, String> headers = new HashMap<>();
            headers.put(HttpConstants.HttpHeaders.SUB_STATUS,
                        Integer.toString(HttpConstants.SubStatusCodes.PARTITION_KEY_RANGE_GONE));
            return BridgeInternal.createCosmosClientException(HttpConstants.StatusCodes.GONE, new CosmosError(), headers);
        }

        protected void capture(String partitionId, CapturedInvocation captureInvocation) {
            partitionKeyRangeIdToCapturedInvocation.put(partitionId, captureInvocation);
        }

        public static RequestExecutor fromPartitionAnswer(List<PartitionAnswer> answers) {
            return new RequestExecutor() {
                @Override
                public Flux<FeedResponse<Document>> apply(RxDocumentServiceRequest request) {
                    synchronized (this) {
                        logger.debug("executing request: " + request + " cp is: " + request.getContinuation());
                        for (PartitionAnswer a : answers) {
                            if (a.getPartitionKeyRangeId().equals(request.getPartitionKeyRangeIdentity().getPartitionKeyRangeId())) {
                                try {
                                    PartitionAnswer.Response resp = a.onRequest(request);
                                    if (resp != null) {
                                        CapturedInvocation ci = new CapturedInvocation(request, resp);
                                        capture(a.getPartitionKeyRangeId(), ci);
                                        return resp.toSingle();
                                    }

                                } catch (Exception e) {
                                    capture(a.getPartitionKeyRangeId(), new CapturedInvocation(request, e));
                                    return Flux.error(e);
                                }
                            }
                        }
                        throw new RuntimeException();
                    }
                }
            };
        }

        public static RequestExecutor fromPartitionAnswer(PartitionAnswer... answers) {
            return fromPartitionAnswer(ImmutableList.copyOf(answers));
        }

        abstract static class PartitionAnswer {
            class Response {
                FeedResponse<Document> invocationResult;
                Exception failureResult;

                public Response(FeedResponse invocationResult) {
                    this.invocationResult = invocationResult;
                }

                public Response(Exception ex) {
                    this.failureResult = ex;
                }

                public Flux<FeedResponse<Document>> toSingle() {
                    if (invocationResult != null) {
                        return Flux.just(invocationResult);
                    } else {
                        return Flux.error(failureResult);
                    }
                }
            }

            private String partitionKeyRangeId;

            private static boolean targetsPartition(RxDocumentServiceRequest req, String partitionKeyRangeId) {
                return partitionKeyRangeId.equals(req.getPartitionKeyRangeIdentity().getPartitionKeyRangeId());
            }

            protected PartitionAnswer(String partitionKeyRangeId) {
                this.partitionKeyRangeId = partitionKeyRangeId;
            }

            public String getPartitionKeyRangeId() {
                return partitionKeyRangeId;
            }

            public abstract Response onRequest(final RxDocumentServiceRequest req);

            public static PartitionAnswer just(String partitionId, List<FeedResponse<Document>> resps) {
                AtomicInteger index = new AtomicInteger();
                return new PartitionAnswer(partitionId) {
                    @Override
                    public Response onRequest(RxDocumentServiceRequest request) {
                        if (!PartitionAnswer.targetsPartition(request, partitionId)) {
                            return null;
                        }
                        synchronized (this) {
                            if (index.get() < resps.size()) {
                                return new Response(resps.get(index.getAndIncrement()));
                            }
                        }
                        return null;
                    }
                };
            }

            public static PartitionAnswer always(String partitionId, final Exception ex) {
                return new PartitionAnswer(partitionId) {
                    @Override
                    public Response onRequest(RxDocumentServiceRequest request) {
                        if (!PartitionAnswer.targetsPartition(request, partitionId)) {
                            return null;
                        }

                        return new Response(ex);
                    }
                };
            }

            public static PartitionAnswer errors(String partitionId, List<Exception> exs) {
                AtomicInteger index = new AtomicInteger();
                return new PartitionAnswer(partitionId) {
                    @Override
                    public Response onRequest(RxDocumentServiceRequest request) {
                        if (!PartitionAnswer.targetsPartition(request, partitionId)) {
                            return null;
                        }
                        synchronized (this) {
                            if (index.get() < exs.size()) {
                                return new Response(exs.get(index.getAndIncrement()));
                            }
                        }
                        return null;
                    }
                };
            }

            public static PartitionAnswer alwaysPartitionSplit(String partitionId) {
                return new PartitionAnswer(partitionId) {
                    @Override
                    public Response onRequest(RxDocumentServiceRequest request) {
                        if (!PartitionAnswer.targetsPartition(request, partitionId)) {
                            return null;
                        }
                        return new Response(partitionKeyRangeGoneException());
                    }
                };
            }
        }
    }

    static abstract class RequestCreator implements TriFunction<PartitionKeyRange, String, Integer,
            RxDocumentServiceRequest> {

        public static RequestCreator give(List<RxDocumentServiceRequest> requests) {
            AtomicInteger i = new AtomicInteger(0);
            return new RequestCreator() {

                @Override
                public RxDocumentServiceRequest apply(PartitionKeyRange pkr, String cp, Integer ps) {
                    synchronized (this) {
                        RxDocumentServiceRequest req = requests.get(i.getAndIncrement());
                        invocations.add(new CapturedInvocation(pkr, cp, ps, req));
                        return req;
                    }
                }
            };
        }

        public static RequestCreator simpleMock() {
            return new RequestCreator() {
                @Override
                public RxDocumentServiceRequest apply(PartitionKeyRange pkr, String cp, Integer ps) {
                    synchronized (this) {
                        RxDocumentServiceRequest req = Mockito.mock(RxDocumentServiceRequest.class);
                        PartitionKeyRangeIdentity pkri = new PartitionKeyRangeIdentity(pkr.getId());
                        doReturn(pkri).when(req).getPartitionKeyRangeIdentity();
                        doReturn(cp).when(req).getContinuation();
                        invocations.add(new CapturedInvocation(pkr, cp, ps, req));
                        logger.debug("creating request: " + req + " cp is " + cp);
                        return req;
                    }
                }
            };
        }

        class CapturedInvocation {
            PartitionKeyRange sourcePartition;
            String continuationToken;
            Integer maxItemCount;
            RxDocumentServiceRequest invocationResult;

            public CapturedInvocation(PartitionKeyRange sourcePartition, String continuationToken,
                    Integer maxItemCount, RxDocumentServiceRequest invocationResult) {
                this.sourcePartition = sourcePartition;
                this.continuationToken = continuationToken;
                this.maxItemCount = maxItemCount;
                this.invocationResult = invocationResult;
            }
        }

        List<CapturedInvocation> invocations = Collections.synchronizedList(new ArrayList<>());

//        abstract public RxDocumentServiceRequest call(PartitionKeyRange pkr, String cp, Integer ps);
    }

    private Document getDocumentDefinition() {
        String uuid = UUID.randomUUID().toString();
        Document doc = new Document(String.format("{ " + "\"id\": \"%s\", " + "\"mypk\": \"%s\", " + "\"sgmts\": " +
                                                          "[[6519456, 1471916863], [2498434, 1455671440]], " +
                                                          "\"prop\": \"%s\"" + "}", uuid, uuid, uuid));
        return doc;
    }
}