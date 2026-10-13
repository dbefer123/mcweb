package dev.mcwebclient.web.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import dev.mcwebclient.web.exec.CoThreads;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;
import org.graalvm.webimage.api.JS;

/**
 * http/https URL connections for the game, carried by the browser's fetch() (JS side:
 * web/dev/host.js, mcweb.http). Web Image's own handler can't serve the game: it returns a
 * plain URLConnection (Minecraft and authlib cast to HttpURLConnection), rejects every proxy
 * including {@link Proxy#NO_PROXY}, and refuses to run on the main thread.
 *
 * <p>A request suspends the calling Java thread until fetch() settles. Hosts that don't
 * allow requests from web pages (CORS) fail like an unreachable host: a
 * {@link ConnectException}, which the game handles like being offline.
 */
public final class WebUrlHandlers implements URLStreamHandlerFactory {
    private WebUrlHandlers() {
    }

    /** Routes http and https through {@link WebHttpConnection}; other protocols keep the defaults. */
    public static void install() {
        URL.setURLStreamHandlerFactory(new WebUrlHandlers());
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            return null;
        }
        int port = "https".equals(protocol) ? 443 : 80;
        return new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new WebHttpConnection(url);
            }

            @Override
            protected URLConnection openConnection(URL url, Proxy proxy) {
                return new WebHttpConnection(url); // the browser picks the route
            }

            @Override
            protected int getDefaultPort() {
                return port;
            }
        };
    }

    /**
     * One fetch() request. Not an HttpsURLConnection even for https: that class sets up the
     * JDK's TLS stack (trust stores) on construction, and TLS is the browser's business.
     */
    static final class WebHttpConnection extends HttpURLConnection {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private byte[] response;
        /** Response headers in arrival order; names as the browser reports them (lower case). */
        private final List<String[]> headers = new ArrayList<>();
        private String statusLine;
        /** Why the request failed; later calls report it again. */
        private IOException failure;

        WebHttpConnection(URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
            if (connected) {
                if (failure != null) {
                    throw failure;
                }
                return;
            }
            String method = getRequestMethod();
            if (doOutput && "GET".equals(method)) {
                method = "POST"; // as the JDK's connection does
            }
            StringBuilder requestHeaders = new StringBuilder();
            boolean contentType = false;
            for (Map.Entry<String, List<String>> e : getRequestProperties().entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                contentType |= e.getKey().equalsIgnoreCase("Content-Type");
                for (String v : e.getValue()) {
                    requestHeaders.append(e.getKey()).append(':').append(v).append('\n');
                }
            }
            byte[] payload = body.size() > 0 || doOutput ? body.toByteArray() : null;
            if (payload != null && !contentType) {
                requestHeaders.append("Content-Type:application/x-www-form-urlencoded\n");
            }
            int timeout = getConnectTimeout() > 0 && getReadTimeout() > 0
                    ? getConnectTimeout() + getReadTimeout()
                    : Math.max(getConnectTimeout(), getReadTimeout());

            int id;
            long tmp = payload != null && payload.length > 0 ? NativeHeap.malloc(payload.length) : 0;
            try {
                if (tmp != 0) {
                    Nmem.copyFromBytes(payload, 0, tmp, payload.length);
                }
                id = hostStart(url.toString(), method, requestHeaders.toString(), (int) tmp,
                        payload == null ? -1 : payload.length, timeout);
            } finally {
                if (tmp != 0) {
                    NativeHeap.free(tmp);
                }
            }

            Thread me = CoThreads.beginIoWait();
            int status = suspendFetch(id);
            CoThreads.endIoWait(me);
            connected = true;
            if (status < 0) {
                String error = hostError(id);
                hostRelease(id);
                failure = error.startsWith("TimeoutError")
                        ? new SocketTimeoutException("HTTP request to " + url.getHost() + " timed out")
                        : new ConnectException("Couldn't reach " + url.getHost()
                                + " (offline, or the host doesn't accept requests from web pages): " + error);
                throw failure;
            }
            responseCode = status;
            responseMessage = hostStatusText(id);
            statusLine = "HTTP/1.1 " + status + (responseMessage.isEmpty() ? "" : " " + responseMessage);
            for (String line : hostHeaders(id).split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.add(new String[] {line.substring(0, colon), line.substring(colon + 1).trim()});
                }
            }
            int length = hostBodyLength(id);
            // fetch() hands over the decoded body: its length is the content length the
            // caller sees (the header, when visible at all, counts compressed bytes).
            headers.removeIf(h -> h[0].equalsIgnoreCase("Content-Length"));
            headers.add(new String[] {"content-length", Integer.toString(Math.max(length, 0))});
            response = new byte[Math.max(length, 0)];
            if (length > 0) {
                long buf = NativeHeap.malloc(length);
                try {
                    hostTakeBody(id, (int) buf);
                    Nmem.copyToBytes(buf, response, 0, length);
                } finally {
                    NativeHeap.free(buf);
                }
            }
            hostRelease(id);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (connected) {
                throw new IllegalStateException("already connected");
            }
            doOutput = true;
            return body;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            if (responseCode >= 400) {
                if (responseCode == 404 || responseCode == 410) {
                    throw new FileNotFoundException(url.toString());
                }
                throw new IOException("Server returned HTTP response code: " + responseCode + " for URL: " + url);
            }
            return new ByteArrayInputStream(response);
        }

        @Override
        public InputStream getErrorStream() {
            return connected && responseCode >= 400 && response.length > 0 ? new ByteArrayInputStream(response) : null;
        }

        @Override
        public int getResponseCode() throws IOException {
            connect();
            return responseCode;
        }

        @Override
        public String getResponseMessage() throws IOException {
            connect();
            return responseMessage;
        }

        @Override
        public String getHeaderField(String name) {
            if (!ensureConnected() || name == null) {
                return null;
            }
            String value = null;
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    value = h[1]; // the last one, as the JDK returns
                }
            }
            return value;
        }

        @Override
        public String getHeaderFieldKey(int n) {
            return !ensureConnected() || n <= 0 || n > headers.size() ? null : headers.get(n - 1)[0];
        }

        @Override
        public String getHeaderField(int n) {
            if (!ensureConnected()) {
                return null;
            }
            return n == 0 ? statusLine : n > headers.size() ? null : headers.get(n - 1)[1];
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            if (!ensureConnected()) {
                return Map.of();
            }
            Map<String, List<String>> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String[] h : headers) {
                fields.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            result.put(null, List.of(statusLine));
            fields.forEach((k, v) -> result.put(k.toLowerCase(Locale.ROOT), Collections.unmodifiableList(v)));
            return Collections.unmodifiableMap(result);
        }

        /** Header getters connect first, like the JDK's; failures read as "no header". */
        private boolean ensureConnected() {
            try {
                connect();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }

    // ---- JS side (web/dev/host.js, mcweb.http) ----

    /** Starts a fetch; bodyLength -1 means no body. Returns the request id. */
    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.start(url, method, headers,"
            + " bodyLength < 0 ? null : new Uint8Array(getExport('nmem').buffer, addr >>> 0, bodyLength).slice(), timeoutMs);",
            args = {"url", "method", "headers", "addr", "bodyLength", "timeoutMs"})
    private static native int hostStart(String url, String method, String headers, int addr, int bodyLength, int timeoutMs);

    /** The response status, or -1 if the request failed (suspends until it settles). */
    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.await(id);", args = {"id"})
    private static native int suspendFetch(int id);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.error(id);", args = {"id"})
    private static native String hostError(int id);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.statusText(id);", args = {"id"})
    private static native String hostStatusText(int id);

    /** Response headers as "name: value" lines. */
    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.headers(id);", args = {"id"})
    private static native String hostHeaders(int id);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb.http.bodyLength(id);", args = {"id"})
    private static native int hostBodyLength(int id);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.http.takeBody(id, getExport('nmem'), addr >>> 0);", args = {"id", "addr"})
    private static native void hostTakeBody(int id, int addr);

    @JS.Coerce
    @JS(value = "globalThis.mcweb.http.release(id);", args = {"id"})
    private static native void hostRelease(int id);
}
