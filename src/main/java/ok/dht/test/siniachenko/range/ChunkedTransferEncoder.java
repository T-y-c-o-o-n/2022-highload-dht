package ok.dht.test.siniachenko.range;

import one.nio.http.Response;

import java.util.Iterator;
import java.util.Map;

public class ChunkedTransferEncoder {
    public EntityChunkStreamQueueItem encodeEntityChunkStream(
        Iterator<Map.Entry<byte[], byte[]>> entryIterator, boolean separatorAfterValue, boolean valueWithMeta
    ) {
        Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        response.addHeader("Connection: close"); // TODO: try to find out how to avoid this
        byte[] metaData = response.toBytes(false);
        return new EntityChunkStreamQueueItem(entryIterator, separatorAfterValue, valueWithMeta, metaData);
    }
}
