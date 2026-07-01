package com.jun.nioServer.message;

import com.jun.nioServer.Connection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MessageDispatcherTest {

    @Mock private MessageReader mockMessageReader;
    @Mock private MessageProcessor mockMessageHandler;
    @Mock private Connection mockConnection;
    @Mock private Message mockRequestMessage;

    private MessageDispatcher messageDispatcher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        messageDispatcher = new MessageDispatcher(mockMessageHandler);
        when(mockRequestMessage.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getMessageReader()).thenReturn(mockMessageReader);
    }

    @Test
    public void processSocketInternal_delegatesToConnectionReader() throws IOException {
        List<ByteBuffer> readData = new ArrayList<>();
        readData.add(ByteBuffer.wrap("TestData".getBytes("UTF-8")));

        when(mockConnection.drainSocketReadData()).thenReturn(readData);
        List<Message> messages = new ArrayList<>(Collections.singletonList(mockRequestMessage));
        when(mockConnection.drainReadyMessages()).thenReturn(messages);

        messageDispatcher.processSocketInternal(mockConnection);

        verify(mockMessageReader).parse(mockConnection, readData);
        verify(mockMessageHandler).processMessage(mockRequestMessage);
    }

    @Test
    public void processSocketInternal_emptyReadData_skipsParse() throws IOException {
        when(mockConnection.drainSocketReadData()).thenReturn(new ArrayList<>());
        when(mockConnection.drainReadyMessages()).thenReturn(new ArrayList<>());

        messageDispatcher.processSocketInternal(mockConnection);

        verify(mockMessageReader, never()).parse(any(Connection.class), anyList());
    }
}
