package com.jun.nioServer.msg.http;

import com.jun.nioServer.msg.IMessageReader;
import com.jun.nioServer.msg.IMessageReaderFactory;

public class HttpMessageReaderFactory implements IMessageReaderFactory {

    @Override
    public IMessageReader createMessageReader() {
        return new HttpMessageReader();
    }
}