package com.jun.nioServer.msg.http;

import org.apache.log4j.Logger;

import java.io.UnsupportedEncodingException;

/**
 * Created by jjenkov on 19-10-2015.
 */
public class HttpUtil {
    private static final Logger log = Logger.getLogger(HttpUtil.class);

    private static final byte[] GET    = new byte[]{'G','E','T'};
    private static final byte[] POST   = new byte[]{'P','O','S','T'};
    private static final byte[] PUT    = new byte[]{'P','U','T'};
    private static final byte[] HEAD   = new byte[]{'H','E','A','D'};
    private static final byte[] DELETE = new byte[]{'D','E','L','E','T','E'};

    private static final byte[] CONTENT_LENGTH = new byte[]{'C','o','n','t','e','n','t','-','L','e','n','g','t','h'};

    public static int parseHttpRequest(byte[] src, int startIndex, int endIndex, HttpHeaders httpHeaders){

        //parse HTTP request line
        int endOfFirstLine = findNextLineBreak(src, startIndex, endIndex);
        if(endOfFirstLine == -1) return -1;

        resolveHttpMethod(src, startIndex, httpHeaders); // Call this to set httpHeaders.httpMethod
        if (httpHeaders.httpMethod == 0) {
            log.warn("No valid HTTP method found in request line.");
            return -1;
        }

        // More robust request line parsing: Method URI Version
        int methodEndIndex = startIndex;
        if (httpHeaders.httpMethod == HttpHeaders.HTTP_METHOD_GET) methodEndIndex += GET.length;
        else if (httpHeaders.httpMethod == HttpHeaders.HTTP_METHOD_POST) methodEndIndex += POST.length;
        else if (httpHeaders.httpMethod == HttpHeaders.HTTP_METHOD_PUT) methodEndIndex += PUT.length;
        else if (httpHeaders.httpMethod == HttpHeaders.HTTP_METHOD_HEAD) methodEndIndex += HEAD.length;
        else if (httpHeaders.httpMethod == HttpHeaders.HTTP_METHOD_DELETE) methodEndIndex += DELETE.length;

        // Find space after method
        int uriStartIndex = -1;
        // endOfFirstLine points to \n, request line content ends at endOfFirstLine - 2 (char before \r)
        // However, loop conditions should use < endOfFirstLine -1 or similar to avoid \r
        int requestLineContentEnd = endOfFirstLine - 1; // The character before \r

        if (methodEndIndex < requestLineContentEnd && src[methodEndIndex] == ' ') {
            uriStartIndex = methodEndIndex + 1;
        } else {
            log.warn("Request line: Malformed - Missing space after method or method length incorrect. MethodEnd: " + methodEndIndex + " ReqLineEnd: " + requestLineContentEnd);
            return -1;
        }

        // Find space after URI (this is where version would start)
        int versionStartIndex = -1;
        boolean foundUriEnd = false;
        for (int i = uriStartIndex; i < requestLineContentEnd; i++) {
            if (src[i] == ' ') {
                if (i == uriStartIndex) {
                    log.warn("Request line: Malformed - URI part is empty.");
                    return -1;
                }
                versionStartIndex = i + 1;
                foundUriEnd = true;
                break;
            }
        }

        if (!foundUriEnd) {
            log.warn("Request line: Malformed - No space found after URI, version is missing.");
            return -1;
        }

        if (versionStartIndex >= requestLineContentEnd) {
            log.warn("Request line: Malformed - HTTP version part is empty.");
            return -1;
        }

        // Minimal check for version starting with HTTP/
        final byte[] HTTP_SLASH = {'H', 'T', 'T', 'P', '/'};
        // Check if enough characters are present for "HTTP/X.Y" (at least HTTP/1.0 which is 8 chars)
        // (requestLineContentEnd - versionStartIndex) is the length of the version part
        if ((requestLineContentEnd - versionStartIndex) < "HTTP/1.0".length() ||
            !matches(src, versionStartIndex, HTTP_SLASH)) {
            try {
                log.warn("Request line: Malformed - HTTP version does not start with 'HTTP/' or is too short. Actual: " + new String(src, versionStartIndex, requestLineContentEnd - versionStartIndex, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                log.warn("Request line: Malformed - HTTP version does not start with 'HTTP/' or is too short. (Unable to log actual string)");
            }
            return -1;
        }
        // TODO: Further validation for HTTP/X.Y can be added here if needed.

        //parse HTTP headers
        int prevEndOfHeader = endOfFirstLine + 1;
        int endOfHeader = findNextLineBreak(src, prevEndOfHeader, endIndex);

        while(endOfHeader != -1 && endOfHeader != prevEndOfHeader + 1){    //prevEndOfHeader + 1 = end of previous header + 2 (+2 = CR + LF)

            if(matches(src, prevEndOfHeader, CONTENT_LENGTH)){
                try {
                    findContentLength(src, prevEndOfHeader, endIndex, httpHeaders);
                } catch (UnsupportedEncodingException e) {
                    log.error("Error parsing content length", e);
                }
            }

            prevEndOfHeader = endOfHeader + 1;
            endOfHeader = findNextLineBreak(src, prevEndOfHeader, endIndex);
        }

        if(endOfHeader == -1){
            return -1;
        }

        //check that byte array contains full HTTP message.
        int bodyStartIndex = endOfHeader + 1;
        int bodyEndIndex  = bodyStartIndex + httpHeaders.contentLength;

        if(bodyEndIndex <= endIndex){
            httpHeaders.bodyStartIndex = bodyStartIndex;
            httpHeaders.bodyEndIndex   = bodyEndIndex;
            return bodyEndIndex;
        }


        return -1;
    }

    private static void findContentLength(byte[] src, int startIndex, int endIndex, HttpHeaders httpHeaders) throws UnsupportedEncodingException {
        int indexOfColon = findNext(src, startIndex, endIndex, (byte) ':');

        int index = indexOfColon +1;
        while(src[index] == ' '){
            index++;
        }

        int valueStartIndex = index;
        int valueEndIndex   = index;
        boolean endOfValueFound = false;

        while(index < endIndex && !endOfValueFound){
            switch(src[index]){
                case '0' : ;
                case '1' : ;
                case '2' : ;
                case '3' : ;
                case '4' : ;
                case '5' : ;
                case '6' : ;
                case '7' : ;
                case '8' : ;
                case '9' : { index++;  break; }

                default: {
                    endOfValueFound = true;
                    valueEndIndex = index;
                }
            }
        }

        httpHeaders.contentLength = Integer.parseInt(new String(src, valueStartIndex, valueEndIndex - valueStartIndex, "UTF-8"));

    }


    public static int findNext(byte[] src, int startIndex, int endIndex, byte value){
        for(int index = startIndex; index < endIndex; index++){
            if(src[index] == value) return index;
        }
        return -1;
    }

    public static int findNextLineBreak(byte[] src, int startIndex, int endIndex) {
        for(int index = startIndex; index < endIndex; index++){
            if(src[index] == '\n'){
                if(src[index - 1] == '\r'){
                    return index;
                }
            };
        }
        return -1;
    }

    public static void resolveHttpMethod(byte[] src, int startIndex, HttpHeaders httpHeaders){
        if(matches(src, startIndex, GET)) {
            httpHeaders.httpMethod = HttpHeaders.HTTP_METHOD_GET;
            return;
        }
        if(matches(src, startIndex, POST)){
            httpHeaders.httpMethod = HttpHeaders.HTTP_METHOD_POST;
            return;
        }
        if(matches(src, startIndex, PUT)){
            httpHeaders.httpMethod = HttpHeaders.HTTP_METHOD_PUT;
            return;
        }
        if(matches(src, startIndex, HEAD)){
            httpHeaders.httpMethod = HttpHeaders.HTTP_METHOD_HEAD;
            return;
        }
        if(matches(src, startIndex, DELETE)){
            httpHeaders.httpMethod = HttpHeaders.HTTP_METHOD_DELETE;
            return;
        }
    }

    public static boolean matches(byte[] src, int offset, byte[] value){
        for(int i=offset, n=0; n < value.length; i++, n++){
            if(src[i] != value[n]) return false;
        }
        return true;
    }
}