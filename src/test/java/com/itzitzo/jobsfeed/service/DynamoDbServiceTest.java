package com.itzitzo.jobsfeed.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoDbServiceTest {
    @Test
    void storesJobsAndTheConditionalMarkerInOneTransaction() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        DynamoDbService store = new DynamoDbService(client, "jobs");
        Job job = job("message", "2026-10-22T12:00:00Z");
        assertTrue(store.saveIngestion("message", List.of(job)));
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        List<TransactWriteItem> writes = request.getValue().transactItems();
        assertEquals(2, writes.size());
        assertEquals("attribute_not_exists(pk)", writes.get(0).put().conditionExpression());
        assertEquals("EMAIL#message", writes.get(0).put().item().get("pk").s());
        assertEquals("FEED#jobs", writes.get(1).put().item().get("pk").s());
        assertEquals(job.sortKey(), writes.get(1).put().item().get("sk").s());
        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void marksNonJobEmailsAndConfirmsOnlyConditionalDuplicateRaces() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        DynamoDbService store = new DynamoDbService(client, "jobs");
        assertTrue(store.saveIngestion("empty", List.of()));
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        assertEquals(1, request.getValue().transactItems().size());
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(
            TransactionCanceledException.builder().cancellationReasons(
                CancellationReason.builder().code("ConditionalCheckFailed").build()).build());
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
            .item(Map.of("pk", AttributeValue.fromS("EMAIL#empty"))).build());
        assertFalse(store.saveIngestion("empty", List.of()));
        ArgumentCaptor<GetItemRequest> read = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(read.capture());
        assertTrue(read.getValue().consistentRead());
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertThrows(TransactionCanceledException.class, () -> store.saveIngestion("empty", List.of()));
    }

    @Test
    void propagatesTransactionFailuresInsteadOfTreatingThemAsDuplicates() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(
            TransactionCanceledException.builder().cancellationReasons(
                CancellationReason.builder().code("TransactionConflict").build()).build());
        assertThrows(TransactionCanceledException.class,
            () -> new DynamoDbService(client, "jobs").saveIngestion("message", List.of()));
        verify(client, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void queriesDescendingAcrossPagesAndRoundTripsTypedJobs() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        DynamoDbService store = new DynamoDbService(client, "jobs");
        Job newest = job("new", "2026-10-22T12:00:00.1Z");
        Job oldest = job("old", "2026-10-22T12:00:00Z");
        assertTrue(newest.sortKey().compareTo(oldest.sortKey()) > 0);
        store.saveIngestion("new", List.of(newest));
        store.saveIngestion("old", List.of(oldest));
        ArgumentCaptor<TransactWriteItemsRequest> writes = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(2)).transactWriteItems(writes.capture());
        Map<String, AttributeValue> cursor = Map.of("pk", AttributeValue.fromS("FEED#jobs"),
            "sk", AttributeValue.fromS(newest.sortKey()));
        when(client.query(any(QueryRequest.class))).thenReturn(
            QueryResponse.builder().items(List.of(writes.getAllValues().get(0).transactItems().get(1).put().item()))
                .lastEvaluatedKey(cursor).build(),
            QueryResponse.builder().items(List.of(writes.getAllValues().get(1).transactItems().get(1).put().item())).build());
        assertEquals(List.of(newest, oldest), store.recentJobs(2));
        ArgumentCaptor<QueryRequest> queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(queries.capture());
        assertFalse(queries.getAllValues().get(0).scanIndexForward());
        assertFalse(queries.getAllValues().get(0).hasExclusiveStartKey());
        assertEquals(1, queries.getAllValues().get(1).limit());
        assertEquals(cursor, queries.getAllValues().get(1).exclusiveStartKey());
        verify(client, never()).scan(any(ScanRequest.class));
    }

    private static Job job(String messageId, String receivedAt) {
        return Job.fromEmail(messageId, Instant.parse(receivedAt), 0,
            new JobDetails("Java Developer", null, "Remote", "Build services.", "https://example.com/job", null));
    }
}
