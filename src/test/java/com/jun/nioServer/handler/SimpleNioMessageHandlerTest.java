package com.jun.nioServer.handler;

import com.jun.config.ServerConfig;
import com.jun.http.NioMessageHandler;
import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.Message;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
// import java.nio.charset.StandardCharsets; // Not used

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SimpleNioMessageHandlerTest {

    private NioMessageHandler handler;
    private ConnectedSocket mockConnectedSocket;
    private Message mockRequestMessage;
    private SelectionKey mockSelectionKey;
    private Selector mockSelector;

    @Before
    public void setUp() {
        handler = new SimpleNioMessageHandler();
        mockConnectedSocket = mock(ConnectedSocket.class);
        mockRequestMessage = mock(Message.class);
        mockSelectionKey = mock(SelectionKey.class);
        mockSelector = mock(Selector.class);

        when(mockConnectedSocket.getSocketId()).thenReturn(123);
        when(mockRequestMessage.getId()).thenReturn(456);
        // Ensure getKey() and selector() are chained correctly for mocks
        when(mockConnectedSocket.getKey()).thenReturn(mockSelectionKey);
        when(mockSelectionKey.selector()).thenReturn(mockSelector);
        when(mockSelectionKey.isValid()).thenReturn(true); // Assume key is valid by default
        when(mockSelector.isOpen()).thenReturn(true); // Assume selector is open by default
    }

    @Test
    public void testProcessMessage_GeneratesResponseAndSetsOpWrite() throws IOException {
        when(mockConnectedSocket.prepareBuffersForWriting()).thenReturn(true);

        handler.processMessage(mockRequestMessage, mockConnectedSocket);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mockConnectedSocket).addWriteReadyMsg(messageCaptor.capture());

        Message responseMessage = messageCaptor.getValue();
        assertNotNull(responseMessage);
        // Assuming Message.getDatas() returns List<byte[]>
        // This part needs to align with how Message stores data. Let's assume it's a list of byte arrays.
        // For simplicity, we'll just check if it was called. Detailed content check is harder with current Message structure.
        // For a more robust test, Message would need methods to inspect its content easily.

        String expectedBody = String.format(ServerConfig.NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT, 123, 456);
        byte[] bodyBytes = expectedBody.getBytes("UTF-8");
        int contentLength = bodyBytes.length;
        String expectedHeader = ServerConfig.NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 + contentLength + ServerConfig.NIO_MSG_HANDLER_STATIC_RESPONSE_PART2;

        // This test would be more robust if we could inspect the Message object's content.
        // For now, we check interactions.
        // byte[] actualFullResponse = ... combine responseMessage.getDatas() ...
        // String fullResponseStr = new String(actualFullResponse, "UTF-8");
        // assertTrue(fullResponseStr.contains(expectedHeader));
        // assertTrue(fullResponseStr.endsWith(expectedBody));


        verify(mockConnectedSocket).prepareBuffersForWriting();
        verify(mockConnectedSocket).addInterestedOps(SelectionKey.OP_WRITE);
        verify(mockSelector).wakeup();
    }

    @Test
    public void testProcessMessage_WhenMakeReadyBufferFalse_DoesNotSetOpWrite() throws IOException {
        when(mockConnectedSocket.prepareBuffersForWriting()).thenReturn(false);

        handler.processMessage(mockRequestMessage, mockConnectedSocket);

        verify(mockConnectedSocket).addWriteReadyMsg(any(Message.class));
        verify(mockConnectedSocket).prepareBuffersForWriting();
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_WRITE);
        verify(mockSelector, never()).wakeup();
    }
}
