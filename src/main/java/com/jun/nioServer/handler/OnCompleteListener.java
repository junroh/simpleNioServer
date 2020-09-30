package com.jun.nioServer.handler;

import java.nio.ByteBuffer;
import java.util.List;

public interface OnCompleteListener {
    void onComplete(int len, List<ByteBuffer> datas);
    void onException(Exception e);
}
