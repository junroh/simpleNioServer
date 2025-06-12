package com.jun.nioServer.handler;

import com.jun.http.NioMessageHandler;
import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.IMessageReader;
import com.jun.nioServer.msg.IMessageReaderFactory;
import com.jun.nioServer.msg.Message;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

public class MsgHandlerTest {

    @Mock private IMessageReaderFactory mockReaderFactory;
    @Mock private IMessageReader mockMessageReader;
    @Mock private NioMessageHandler mockNioMessageHandler;
    @Mock private ConnectedSocket mockConnectedSocket;
    @Mock private Message mockRequestMessage; // Renamed for clarity from mockMessage to mockRequestMessage

    private MsgHandler msgHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mockReaderFactory.createMessageReader()).thenReturn(mockMessageReader);
        msgHandler = new MsgHandler(mockReaderFactory, mockNioMessageHandler);

        // Basic setup for mockRequestMessage, assuming it might be used
        // If mockRequestMessage.socketChannel is accessed, it needs to be mocked.
        // For processMessage(mockRequestMessage, mockRequestMessage.socketChannel),
        // we would need: when(mockRequestMessage.socketChannel).thenReturn(mockConnectedSocket);
        // However, the test passes mockConnectedSocket directly or via the message object.
        // Let's ensure mockRequestMessage.socketChannel returns the main mockConnectedSocket
        // if that's the intended interaction pattern from the refactored MsgHandler.process().
        // From `this.messageProcessor.processMessage(message, message.socketChannel);`
        // it implies `message.socketChannel` is used.
        when(mockRequestMessage.socketChannel).thenReturn(mockConnectedSocket);

    }

    @Test
    public void testProcessSocketInternal_ParsesAndProcessesMessage() throws IOException {
        List<ByteBuffer> readData = new ArrayList<>();
        // Use a Charset consistent with Message processing if specific encoding is assumed downstream
        readData.add(ByteBuffer.wrap("TestData".getBytes("UTF-8")));

        when(mockConnectedSocket.getSocketReadData()).thenReturn(readData);
        // Assume first buffer completes a message, parse returns index of this buffer
        when(mockMessageReader.parse(mockConnectedSocket, readData)).thenReturn(0);

        List<Message> messages = Collections.singletonList(mockRequestMessage);
        when(mockConnectedSocket.getReadReadyMessages()).thenReturn(messages);

        msgHandler.processSocketInternal(mockConnectedSocket);

        verify(mockMessageReader).parse(mockConnectedSocket, readData);
        verify(mockConnectedSocket).getReadReadyMessages();
        // Verify with the specific mockRequestMessage and its associated (mocked) socketChannel
        verify(mockNioMessageHandler).processMessage(mockRequestMessage, mockRequestMessage.socketChannel);
    }

    @Test
    public void testProcessSocketInternal_NoCompleteMessage_DoesNotProcess() throws IOException {
        List<ByteBuffer> readData = new ArrayList<>();
        readData.add(ByteBuffer.wrap("IncompleteData".getBytes("UTF-8")));

        when(mockConnectedSocket.getSocketReadData()).thenReturn(readData);
        // No complete message found by parser
        when(mockMessageReader.parse(mockConnectedSocket, readData)).thenReturn(-1);

        msgHandler.processSocketInternal(mockConnectedSocket);

        verify(mockMessageReader).parse(mockConnectedSocket, readData);
        // Should not proceed to get messages or process them if parsing finds no complete message
        verify(mockConnectedSocket, never()).getReadReadyMessages();
        verify(mockNioMessageHandler, never()).processMessage(any(Message.class), any(ConnectedSocket.class));
    }
}
