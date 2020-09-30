package com.jun.nioServer.msg.http;

import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.IMessageReader;
import com.jun.nioServer.msg.Message;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;

public class HttpMessageReader implements IMessageReader {

    private static final Logger log = Logger.getLogger(HttpMessageReader.class);
    public HttpMessageReader() {

    }

    @Override
    public int parse(ConnectedSocket socket, List<ByteBuffer> buffers) {
        Message msg = new Message(socket);
        msg.setHeader(new HttpHeaders());
        int idx = 0;
        int lasCompIdx = -1;
        for(ByteBuffer buffer: buffers) {
            int startIdx = 0;
            int remaining = buffer.limit();
            do {
                HttpHeaders headers = (HttpHeaders) msg.getHeader();
                boolean isCompletedMsg = false;
                int endIdx = HttpUtil.parseHttpRequest(buffer.array(), startIdx, remaining, headers);
                if (endIdx != -1) {
                    isCompletedMsg = true;
                } else {
                    log.debug("data is not completed yet");
                    endIdx = remaining;
                }
                msg.writeToMessage(buffer.array(), startIdx, endIdx);
                remaining -= (endIdx - startIdx);
                if (isCompletedMsg) {
                    lasCompIdx = idx;
                    socket.addReadReadyMsg(msg);
                    if (remaining != 0) {
                        log.debug("There is a remaining data");
                        msg = new Message(socket);
                        msg.setHeader(new HttpHeaders());
                    }
                }
                startIdx = endIdx;
            } while (remaining != 0);
            idx++;
        }
        return lasCompIdx;
    }
}
