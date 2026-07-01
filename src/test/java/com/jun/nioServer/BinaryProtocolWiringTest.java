package com.jun.nioServer;

import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageProcessor;
import com.jun.nioServer.message.MessageReader;
import com.jun.nioServer.message.MessageReaderFactory;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Proves that {@link NioServerService} accepts an arbitrary protocol — not just HTTP —
 * via its generic constructor. We don't bind a socket here; we only verify the wiring
 * compiles and the service is constructable with a custom binary protocol.
 */
public class BinaryProtocolWiringTest {

    /** A toy binary "parser": echoes every byte chunk as a single-message payload. */
    private static final class EchoBinaryReader implements MessageReader {
        @Override
        public void parse(Connection connection, List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
                byte[] copy = new byte[b.remaining()];
                b.get(copy);
                Message m = new Message(connection);
                m.setPayload(copy);
                connection.addReadReadyMsg(m);
            }
        }
    }

    private static final class EchoBinaryProcessor implements MessageProcessor {
        @Override
        public void processMessage(Message message) {
            byte[] payload = message.getPayloadAs(byte[].class);
            Message response = new Message(message.getConnection(), message.getId());
            response.writeToMessage(payload);
            // In a real run this would call connection.sendResponse(response).
        }
    }

    @Test
    public void nioServerServiceAcceptsBinaryProtocolWiring() {
        NioServerSettings settings = NioServerSettings.builder().port(0).build();
        NioServerService service = new NioServerService(
                settings,
                EchoBinaryReader::new,                 // MessageReaderFactory (lambda)
                new EchoBinaryProcessor());
        assertNotNull(service);
    }

    @Test
    public void factoryProducesFreshReadersPerConnection() {
        MessageReaderFactory factory = EchoBinaryReader::new;
        MessageReader r1 = factory.createMessageReader();
        MessageReader r2 = factory.createMessageReader();
        assertNotNull(r1);
        assertNotNull(r2);
        // Two factory calls produce two distinct reader instances, so per-connection
        // parser state doesn't leak across connections.
        org.junit.Assert.assertNotSame(r1, r2);
    }

    @Test
    public void messagePayloadRoundTripsForBinaryType() {
        // Connection isn't strictly needed for payload round-trip; pass null and only
        // exercise the typed-payload API on Message itself.
        Message m = new Message((Connection) null);
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        m.setPayload(data);

        byte[] back = m.getPayloadAs(byte[].class);
        assertSame(data, back);
    }
}
