/*
 * RED5 Open Source Flash Server - https://github.com/red5 Copyright 2006-2018 by respective authors (see below). All rights reserved. Licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless
 * required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package org.red5.net.websocket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.stream.Stream;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCode;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.Extension;
import javax.websocket.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsSession;
import org.red5.server.util.AttributeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocketConnection <br>
 * This class represents a WebSocket connection with a client (browser).
 *
 * @see <a href="https://tools.ietf.org/html/rfc6455">rfc6455</a>
 *
 * @author Paul Gregoire
 */
public class WebSocketConnection extends AttributeStore implements Comparable<WebSocketConnection> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnection.class);

    private static final boolean isTrace = log.isTraceEnabled();

    private static final boolean isDebug = log.isDebugEnabled();

    // Sending async on windows times out
    private static boolean useAsync;

    private static long sendTimeout = 8000L, readTimeout = 30000L;

    private static final AtomicLongFieldUpdater<WebSocketConnection> readBytesUpdater = AtomicLongFieldUpdater.newUpdater(WebSocketConnection.class, "readBytes");

    private static final AtomicLongFieldUpdater<WebSocketConnection> writeBytesUpdater = AtomicLongFieldUpdater.newUpdater(WebSocketConnection.class, "writtenBytes");

    private AtomicBoolean connected = new AtomicBoolean(false);

    // associated websocket session
    private final WsSession wsSession;

    // reference to the scope for manager access
    private WeakReference<WebSocketScope> scope;

    // unique identifier for the session
    private final String wsSessionId;

    // unique identifier for this instance based upon the websocket session id
    private final int hashCode;

    private String host;

    private String path;

    private String origin;

    private String userAgent = "undefined";

    /**
     * Contains http headers and other web-socket information from the initial request.
     */
    private Map<String, List<String>> headers;

    private Map<String, Object> extensions = new HashMap<>();

    /**
     * Contains uri parameters from the initial request.
     */
    private Map<String, Object> querystringParameters = new HashMap<>();

    /**
     * Connection protocol (ex. chat, json, etc)
     */
    private String protocol;

    // stats
    private volatile long readBytes, writtenBytes;

    // send future for when async is enabled
    private Future<Void> sendFuture;

    public WebSocketConnection(WebSocketScope scope, Session session) {
        log.debug("New WebSocket - scope: {} session: {}", scope, session);
        // set the scope for ease of use later
        this.scope = new WeakReference<>(scope);
        // set our path
        path = scope.getPath();
        if (isDebug) {
            log.debug("path: {}", path);
        }
        // cast ws session
        this.wsSession = (WsSession) session;
        if (isDebug) {
            log.debug("ws session: {}", wsSession);
        }
        // the websocket session id will be used for hash code comparison, its the only usable value currently
        wsSessionId = session.getId();
        if (isDebug) {
            log.debug("wsSessionId: {}", wsSessionId);
        }
        hashCode = wsSessionId.hashCode();
        log.info("ws id: {} hashCode: {}", wsSessionId, hashCode);
        // get extensions
        List<Extension> extList = session.getNegotiatedExtensions();
        if (extList != null) {
            extList.forEach(extension -> {
                extensions.put(extension.getName(), extension);
            });
        }
        if (isDebug) {
            log.debug("extensions: {}", extensions);
        }
        // get querystring
        String queryString = session.getQueryString();
        if (isDebug) {
            log.debug("queryString: {}", queryString);
        }
        if (StringUtils.isNotBlank(queryString)) {
            // bust it up by ampersand
            String[] qsParams = queryString.split("&");
            // loop-thru adding to the local map
            Stream.of(qsParams).forEach(qsParam -> {
                String[] parts = qsParam.split("=");
                if (parts.length == 2) {
                    querystringParameters.put(parts[0], parts[1]);
                } else {
                    querystringParameters.put(parts[0], null);
                }
            });
        }
        // get request parameters
        Map<String, String> pathParameters = session.getPathParameters();
        if (isDebug) {
            log.debug("pathParameters: {}", pathParameters);
        }
        // get user props
        Map<String, Object> userProps = session.getUserProperties();
        // add the timeouts to the user props
        userProps.put(Constants.READ_IDLE_TIMEOUT_MS, readTimeout);
        userProps.put(Constants.WRITE_IDLE_TIMEOUT_MS, sendTimeout);
        // set the close timeout to 5 seconds
        userProps.put(Constants.SESSION_CLOSE_TIMEOUT_PROPERTY, TimeUnit.SECONDS.toMillis(5));
        if (isDebug) {
            log.debug("userProps: {}", userProps);
        }
        // set maximum messages size to 10,000 bytes
        session.setMaxTextMessageBufferSize(10000);
        // set maximum idle timeout to 30 seconds (read timeout)
        session.setMaxIdleTimeout(readTimeout);
    }

    /**
     * Sends text to the client.
     *
     * @param data
     *            string / text data
     * @throws UnsupportedEncodingException
     * @throws IOException
     */
    public void send(String data) throws UnsupportedEncodingException, IOException {
        if (isDebug) {
            log.debug("send message: {}", data);
        }
        // process the incoming string
        if (StringUtils.isNotBlank(data)) {
            // attempt send only if the session is not closed
            if (!wsSession.isClosed()) {
                try {
                    if (useAsync) {
                        if (sendFuture != null && !sendFuture.isDone()) {
                            try {
                                sendFuture.get(sendTimeout, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException e) {
                                log.warn("Send timed out {}", wsSessionId);
                                // if the session is not open, cancel the future
                                if (!wsSession.isOpen()) {
                                    sendFuture.cancel(true);
                                    return;
                                }
                            }
                        }
                        synchronized (wsSessionId) {
                            int lengthToWrite = data.getBytes().length;
                            sendFuture = wsSession.getAsyncRemote().sendText(data);
                            updateWriteBytes(lengthToWrite);
                        }
                    } else {
                        synchronized (wsSessionId) {
                            int lengthToWrite = data.getBytes().length;
                            wsSession.getBasicRemote().sendText(data);
                            updateWriteBytes(lengthToWrite);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Send text exception", e);
                }
            } else {
                throw new IOException("WS session closed");
            }
        } else {
            throw new UnsupportedEncodingException("Cannot send a null string");
        }
    }

    /**
     * Sends binary data to the client.
     *
     * @param buf
     * @throws IOException
     */
    public void send(byte[] buf) throws IOException {
        if (isDebug) {
            log.debug("send binary: {}", Arrays.toString(buf));
        }
        if (!wsSession.isClosed()) {
            try {
                // send the bytes
                if (useAsync) {
                    if (sendFuture != null && !sendFuture.isDone()) {
                        try {
                            sendFuture.get(sendTimeout, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            log.warn("Send timed out {}", wsSessionId);
                            if (!isConnected()) {
                                sendFuture.cancel(true);
                                return;
                            }
                        }
                    }
                    synchronized (wsSessionId) {
                        sendFuture = wsSession.getAsyncRemote().sendBinary(ByteBuffer.wrap(buf));
                        updateWriteBytes(buf.length);
                    }
                } else {
                    synchronized (wsSessionId) {
                        wsSession.getBasicRemote().sendBinary(ByteBuffer.wrap(buf));
                        updateWriteBytes(buf.length);
                    }
                }
            } catch (Exception e) {
                log.warn("Send bytes exception", e);
            }
        } else {
            throw new IOException("WS session closed");
        }
    }

    /**
     * Sends a ping to the client.
     *
     * @param buf
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void sendPing(byte[] buf) throws IllegalArgumentException, IOException {
        if (isTrace) {
            log.trace("send ping: {}", buf);
        }
        if (!wsSession.isClosed()) {
            synchronized (wsSessionId) {
                // send the bytes
                wsSession.getBasicRemote().sendPing(ByteBuffer.wrap(buf));
                // update counter
                updateWriteBytes(buf.length);
            }
        } else {
            throw new IOException("WS session closed");
        }
    }

    /**
     * Sends a pong back to the client; normally in response to a ping.
     *
     * @param buf
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public void sendPong(byte[] buf) throws IllegalArgumentException, IOException {
        if (isTrace) {
            log.trace("send pong: {}", buf);
        }
        if (!wsSession.isClosed()) {
            synchronized (wsSessionId) {
                // send the bytes
                wsSession.getBasicRemote().sendPong(ByteBuffer.wrap(buf));
                // update counter
                updateWriteBytes(buf.length);
            }
        } else {
            throw new IOException("WS session closed");
        }
    }

    /**
     * Close the connection.
     */
    public void close() {
        close(CloseCodes.NORMAL_CLOSURE, "");
    }

    /**
     * Close the connection with a reason.
     *
     * @param code CloseCode
     * @param reasonPhrase short reason for closing
     */
    public void close(CloseCode code, String reasonPhrase) {
        if (connected.compareAndSet(true, false)) {
            // no blank reasons
            if (reasonPhrase == null) {
                reasonPhrase = "";
            }
            log.debug("close: {} code: {} reason: {}", wsSessionId, code, reasonPhrase);
            try {
                // close the session if open
                if (wsSession.isOpen()) {
                    CloseReason reason = new CloseReason(code, reasonPhrase);
                    if (isDebug) {
                        log.debug("Closing session: {} with reason: {}", wsSessionId, reason);
                    }
                    wsSession.close(reason);
                }
            } catch (Exception e) {
                log.debug("Exception closing session", e);
            }
            // clean up our props
            attributes.clear();
            if (querystringParameters != null) {
                querystringParameters.clear();
                querystringParameters = null;
            }
            if (extensions != null) {
                extensions.clear();
                extensions = null;
            }
            if (headers != null) {
                headers = null;
            }
        }
    }

    /**
     * Async send is enabled in non-Windows based systems; this provides a means to override it.
     *
     * @param useAsync
     */
    public static void setUseAsync(boolean useAsync) {
        if (!useAsync) {
            log.debug("Async websocket sends are disabled");
        }
        WebSocketConnection.useAsync = useAsync;
    }

    /**
     * Return the WebSocketScope to which we're connected/connecting.
     *
     * @return WebSocketScope
     */
    public WebSocketScope getScope() {
        return scope != null ? scope.get() : null;
    }

    /**
     * @return the connected
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * On connected, set flag.
     */
    public void setConnected() {
        boolean connectSuccess = connected.compareAndSet(false, true);
        log.debug("Connect success: {}", connectSuccess);
    }

    /**
     * @return the host
     */
    public String getHost() {
        return String.format("%s://%s%s", (isSecure() ? "wss" : "ws"), host, path);
    }

    /**
     * @param host
     *            the host to set
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * @return the origin
     */
    public String getOrigin() {
        return origin;
    }

    /**
     * @param origin
     *            the origin to set
     */
    public void setOrigin(String origin) {
        this.origin = origin;
    }

    /**
     * Return whether or not the session is secure.
     *
     * @return true if secure and false if unsecure or unconnected
     */
    public boolean isSecure() {
        Optional<WsSession> opt = Optional.ofNullable(wsSession);
        if (opt.isPresent()) {
            return (opt.get().isOpen() ? opt.get().isSecure() : false);
        }
        return false;
    }

    public String getPath() {
        return path;
    }

    /**
     * @param path
     *            the path to set
     */
    public void setPath(String path) {
        if (path.charAt(path.length() - 1) == '/') {
            this.path = path.substring(0, path.length() - 1);
        } else {
            this.path = path;
        }
    }

    /**
     * Returns the WsSession id associated with this connection.
     *
     * @return sessionId
     */
    public String getSessionId() {
        return wsSessionId;
    }

    /**
     * Sets / overrides this connections HttpSession id.
     *
     * @param httpSessionId
     * @deprecated Session id read from WSSession
     */
    @Deprecated(since = "1.2.26")
    public void setHttpSessionId(String httpSessionId) {
        //this.httpSessionId = httpSessionId;
    }

    /**
     * Returns the HttpSession id associated with this connection.
     *
     * @return sessionId
     * @deprecated Session id read from WSSession
     */
    @Deprecated(since = "1.2.26")
    public String getHttpSessionId() {
        return wsSessionId;
    }

    /**
     * Returns the user agent.
     *
     * @return userAgent
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Sets the incoming headers.
     *
     * @param headers
     */
    public void setHeaders(Map<String, List<String>> headers) {
        if (headers != null && !headers.isEmpty()) {
            // look for both upper and lower case
            List<String> userAgentHeader = Optional.ofNullable(headers.get(WSConstants.HTTP_HEADER_USERAGENT)).orElse(headers.get(WSConstants.HTTP_HEADER_USERAGENT.toLowerCase()));
            if (userAgentHeader != null && !userAgentHeader.isEmpty()) {
                userAgent = userAgentHeader.get(0);
            }
            List<String> hostHeader = Optional.ofNullable(headers.get(Constants.HOST_HEADER_NAME)).orElse(headers.get(Constants.HOST_HEADER_NAME.toLowerCase()));
            if (hostHeader != null && !hostHeader.isEmpty()) {
                host = hostHeader.get(0);
            }
            List<String> originHeader = Optional.ofNullable(headers.get(Constants.ORIGIN_HEADER_NAME)).orElse(headers.get(Constants.ORIGIN_HEADER_NAME.toLowerCase()));
            if (originHeader != null && !originHeader.isEmpty()) {
                origin = originHeader.get(0);
            }
            Optional<List<String>> protocolHeader = Optional.ofNullable(headers.get(WSConstants.WS_HEADER_PROTOCOL));
            if (protocolHeader.isPresent()) {
                if (isDebug) {
                    log.debug("Protocol header(s) exist: {}", protocolHeader.get());
                }
                protocol = protocolHeader.get().get(0);
            }
            if (isDebug) {
                log.debug("Set from headers - user-agent: {} host: {} origin: {}", userAgent, host, origin);
            }
            this.headers = headers;
        } else {
            this.headers = Collections.emptyMap();
        }
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Map<String, Object> getQuerystringParameters() {
        return querystringParameters;
    }

    public void setQuerystringParameters(Map<String, Object> querystringParameters) {
        if (this.querystringParameters == null) {
            this.querystringParameters = new ConcurrentHashMap<>();
        }
        this.querystringParameters.putAll(querystringParameters);
    }

    /**
     * Returns whether or not extensions are enabled on this connection.
     *
     * @return true if extensions are enabled, false otherwise
     */
    public boolean hasExtensions() {
        return extensions != null && !extensions.isEmpty();
    }

    /**
     * Returns enabled extensions.
     *
     * @return extensions
     */
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    /**
     * Sets the extensions.
     *
     * @param extensions
     */
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    /**
     * Returns the extensions list as a comma separated string as specified by the rfc.
     *
     * @return extension list string or null if no extensions are enabled
     */
    public String getExtensionsAsString() {
        String extensionsList = null;
        if (extensions != null) {
            StringBuilder sb = new StringBuilder();
            for (String key : extensions.keySet()) {
                sb.append(key);
                sb.append("; ");
            }
            extensionsList = sb.toString().trim();
        }
        return extensionsList;
    }

    /**
     * Returns whether or not a protocol is enabled on this connection.
     *
     * @return true if protocol is enabled, false otherwise
     */
    public boolean hasProtocol() {
        return protocol != null;
    }

    /**
     * Returns the protocol enabled on this connection.
     *
     * @return protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Sets the protocol.
     *
     * @param protocol
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public static long getSendTimeout() {
        return sendTimeout;
    }

    public static void setSendTimeout(long sendTimeout) {
        WebSocketConnection.sendTimeout = sendTimeout;
    }

    public static long getReadTimeout() {
        return readTimeout;
    }

    public static void setReadTimeout(long readTimeout) {
        WebSocketConnection.readTimeout = readTimeout;
    }

    public void setUserProperty(String key, Object value) {
        WsSession wsSession = getWsSession();
        if (wsSession != null) {
            wsSession.getUserProperties().put(key, value);
        }
    }

    public Object getUserProperty(String key) {
        WsSession wsSession = getWsSession();
        if (wsSession.getUserProperties().get(key) != null) {
            return wsSession.getUserProperties().get(key);
        }
        return null;
    }

    public void setWsSessionTimeout(long idleTimeout) {
        if (wsSession != null) {
            wsSession.setMaxIdleTimeout(idleTimeout);
        }
    }

    public WsSession getWsSession() {
        return wsSession != null ? wsSession : null;
    }

    public long getReadBytes() {
        return readBytes;
    }

    public void updateReadBytes(long read) {
        log.debug("updateReadBytes: {} by: {}", readBytes, read);
        readBytesUpdater.addAndGet(this, read);
        // read time is updated on WsSession by WsFrameBase when the read is performed
    }

    public long getWrittenBytes() {
        return writtenBytes;
    }

    public void updateWriteBytes(long wrote) {
        log.debug("updateWriteBytes: {} by: {}", writtenBytes, wrote);
        writeBytesUpdater.addAndGet(this, wrote);
        // write time is updated on WsSession by WsRemoteEndpointImplBase when the write is performed
    }

    public String getWsSessionId() {
        return wsSessionId;
    }

    @Override
    public int compareTo(WebSocketConnection that) {
        return Integer.compare(hashCode, that.hashCode);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        WebSocketConnection other = (WebSocketConnection) obj;
        return hashCode == other.hashCode();
    }

    @Override
    public String toString() {
        if (wsSessionId != null) {
            return "WebSocketConnection [wsId=" + wsSessionId + ", host=" + host + ", origin=" + origin + ", path=" + path + ", secure=" + isSecure() + ", connected=" + connected + "]";
        }
        if (wsSession == null) {
            return "WebSocketConnection [wsId=not-set, host=" + host + ", origin=" + origin + ", path=" + path + ", secure=not-set, connected=" + connected + "]";
        }
        return "WebSocketConnection [host=" + host + ", origin=" + origin + ", path=" + path + " connected=false]";
    }

}
