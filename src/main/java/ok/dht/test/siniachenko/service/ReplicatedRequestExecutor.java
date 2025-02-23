package ok.dht.test.siniachenko.service;

import ok.dht.test.siniachenko.TycoonHttpServer;
import ok.dht.test.siniachenko.exception.NotEnoughReplicasException;
import ok.dht.test.siniachenko.hintedhandoff.Hint;
import ok.dht.test.siniachenko.hintedhandoff.HintsManager;
import ok.dht.test.siniachenko.nodemapper.NodeMapper;
import ok.dht.test.siniachenko.nodetaskmanager.NodeTaskManager;
import one.nio.http.Request;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ReplicatedRequestExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicatedRequestExecutor.class);
    private static final Map<String, Set<Integer>> METHOD_NAME_TO_SUCCESS_STATUS_CODES = Map.of(
        "GET", Set.of(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NOT_FOUND),
        "PUT", Collections.singleton(HttpURLConnection.HTTP_CREATED),
        "DELETE", Collections.singleton(HttpURLConnection.HTTP_ACCEPTED)
    );

    private final ExecutorService executorService;
    private final Request request;
    private final String id;
    private final Supplier<byte[]> localWork;
    private final int ack;
    private final int from;

    private Set<Integer> successStatusCodes;
    private AtomicInteger failureCount;
    private AtomicInteger successCount;
    private AtomicInteger readyCount;
    byte[][] values;

    // Lazy initialized field just for HintManager
    private Hint hint;

    public ReplicatedRequestExecutor(
        ExecutorService executorService, Request request, String id,
        Supplier<byte[]> localWork, int ack, int from
    ) {
        this.executorService = executorService;
        this.request = request;
        this.id = id;
        this.localWork = localWork;
        this.ack = ack;
        this.from = from;
    }

    public CompletableFuture<byte[][]> execute(
        String selfUrl, NodeMapper nodeMapper, NodeTaskManager nodeTaskManager,
        HttpClient httpClient, HintsManager hintsManager
    ) {
        successStatusCodes = METHOD_NAME_TO_SUCCESS_STATUS_CODES.get(request.getMethodName());

        values = new byte[from][];
        boolean needLocalWork = false;
        failureCount = new AtomicInteger();
        successCount = new AtomicInteger();
        readyCount = new AtomicInteger();

        CompletableFuture<byte[][]> resultFuture = new CompletableFuture<>();

        NodeMapper.Shard[] shards = nodeMapper.shards;
        int nodeIndex = nodeMapper.getIndexForKey(Utf8.toBytes(id));
        for (int replicaIndex = 0; replicaIndex < from; ++replicaIndex) {
            NodeMapper.Shard shard = shards[(nodeIndex + replicaIndex) % shards.length];
            String nodeUrlByKey = shard.getUrl();
            if (selfUrl.equals(nodeUrlByKey)) {
                needLocalWork = true;
            } else {
                proxyAndHandle(nodeTaskManager, httpClient, hintsManager, nodeUrlByKey, resultFuture);
            }
        }

        if (needLocalWork) {
            CompletableFuture.supplyAsync(localWork, executorService)
                .exceptionally(e -> {
                        LOG.error("Error after executing replicated request locally", e);
                        addFailure(resultFuture);
                        return null;
                    }
                ).thenAccept(value -> {
                    // could reuse addSuccess method, but code climate wanted to reallocate all arrays...
                    int success = successCount.incrementAndGet();
                    values[success - 1] = value;
                    // second atomic is to avoid case when ack threads incremented success,
                    // but somebody hasn't set result yet
                    int ready = readyCount.incrementAndGet();
                    if (ready == ack) { // first achieve of ack success results
                        resultFuture.complete(values);
                    }
                });
        }

        return resultFuture;
    }

    private void proxyAndHandle(
        NodeTaskManager nodeTaskManager, HttpClient httpClient, HintsManager hintsManager,
        String nodeUrlByKey, CompletableFuture<byte[][]> resultFuture
    ) {
        boolean taskAdded = nodeTaskManager.tryAddNodeTask(
            nodeUrlByKey,
            () -> proxyRequest(
                request, id, nodeUrlByKey, httpClient
            ).exceptionallyAsync(e -> {
                    LOG.error("Error after proxy request to {}", nodeUrlByKey, e);
                    handleReplicaFailure(nodeUrlByKey, hintsManager, resultFuture);
                    return null;
                }, executorService
            ).thenAcceptAsync(response -> {
                    if (successStatusCodes.contains(response.statusCode())) {
                        addSuccess(response, resultFuture);
                    } else {
                        LOG.error(
                            "Unexpected status code {} after proxy request to {}",
                            response.statusCode(),
                            nodeUrlByKey
                        );
                        handleReplicaFailure(nodeUrlByKey, hintsManager, resultFuture);
                    }
                }, executorService
            )
        );

        if (!taskAdded) {
            // Couldn't schedule task for the node
            handleReplicaFailure(nodeUrlByKey, hintsManager, resultFuture);
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> proxyRequest(
        Request request, String idParameter, String nodeUrl, HttpClient httpClient
    ) {
        return httpClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(nodeUrl + TycoonHttpServer.ENTITY_SERVICE_PATH + "?id=" + idParameter))
                .method(
                    request.getMethodName(),
                    request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .header(TycoonHttpServer.REQUEST_TO_REPLICA_HEADER, "")
                .build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private void addSuccess(HttpResponse<byte[]> response, CompletableFuture<byte[][]> resultFuture) {
        int success = successCount.incrementAndGet();
        values[success - 1] = response.body();
        // second atomic is to avoid case when ack threads incremented success, but somebody hasn't set result yet
        int ready = readyCount.incrementAndGet();
        if (ready == ack) { // first achieve of ack success results
            resultFuture.complete(values);
        }
    }

    private void handleReplicaFailure(
        String replicaUrl, HintsManager hintsManager, CompletableFuture<byte[][]> resultFuture
    ) {
        if (request.getBody() != null && request.getBody().length > 0) {
            addHint(replicaUrl, hintsManager);
        }
        addFailure(resultFuture);
    }

    private void addFailure(CompletableFuture<byte[][]> resultFuture) {
        int failure = failureCount.incrementAndGet();
        if (from - failure == ack - 1) { // first time when cannot achieve enough results
            resultFuture.completeExceptionally(new NotEnoughReplicasException());
        }
    }

    private void addHint(String replicaUrl, HintsManager hintsManager) {
        if (hint == null) {
            hint = new Hint();
            hint.key = Utf8.toBytes(id);
            hint.value = request.getBody();
        }
        hintsManager.addHintForReplica(replicaUrl, hint);
    }
}
