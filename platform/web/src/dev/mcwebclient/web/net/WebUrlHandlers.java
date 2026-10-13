package dev.mcwebclient.web.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * http/https URL connections for the game. Web Image's own handler can't serve the
 * game: it returns a plain URLConnection (Minecraft and authlib cast to
 * HttpURLConnection), rejects every proxy including {@link Proxy#NO_PROXY}, and refuses
 * to run on the main thread.
 *
 * <p>For now requests fail at connect time with a {@link ConnectException}, which the
 * game handles like being offline (Realms, profile lookups, telemetry). Request bodies
 * are buffered so a real transport can be added behind {@link #connect} later.
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

    /** An HTTP(S) connection whose transport is not available yet. */
    static final class WebHttpConnection extends HttpURLConnection {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        WebHttpConnection(URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
            throw new ConnectException("HTTP from the browser build is not available yet: " + url.getHost());
        }

        @Override
        public OutputStream getOutputStream() {
            return body;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            throw new IllegalStateException("unreachable");
        }

        @Override
        public InputStream getErrorStream() {
            return null;
        }

        @Override
        public int getResponseCode() throws IOException {
            connect();
            return -1;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}
