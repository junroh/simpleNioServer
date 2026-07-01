package com.jun.nioServer.http;

import com.jun.nioServer.message.MessageReader;
import com.jun.nioServer.message.MessageReaderFactory;

/** Creates one {@link HttpNioMessageReader} per connection. */
public class HttpNioMessageReaderFactory implements MessageReaderFactory {
    @Override
    public MessageReader createMessageReader() {
        return new HttpNioMessageReader();
    }
}
