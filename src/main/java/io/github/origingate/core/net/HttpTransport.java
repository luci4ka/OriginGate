package io.github.origingate.core.net;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking I/O for worker threads, with no automatic redirects. The request timeout covers name lookup, connecting,
 * and the response, including a body that the handler reads in full. A body returned as a stream has no total
 * deadline, only the timeout on each read, as with java.net.http. Exchanges run on their own threads, so the caller
 * returns on time even when a socket call cannot be interrupted. A body read to the end keeps the connection for reuse.
 */
public final class HttpTransport implements AutoCloseable {
    private final int connectMillis;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService exchanges = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "OriginGate HTTP"); thread.setDaemon(true); return thread;
    });
    private HttpTransport(Duration connectTimeout) {
        connectMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, connectTimeout.toMillis()));
    }
    public static HttpTransport newHttpClient() { return newBuilder().build(); }
    public static Builder newBuilder() { return new Builder(); }
    public static final class Builder {
        private Duration timeout = Duration.ofSeconds(10);
        public Builder connectTimeout(Duration value) { timeout = value; return this; }
        public HttpTransport build() { return new HttpTransport(timeout); }
    }
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (closed.get()) throw new IOException("HTTP transport is closed");
        final HttpURLConnection connection = (HttpURLConnection) request.uri.toURL().openConnection();
        final int wait = (int) Math.min(Integer.MAX_VALUE, Math.max(1, request.timeout.toMillis()));
        connection.setConnectTimeout(Math.min(connectMillis, wait));
        connection.setReadTimeout(wait);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(request.method);
        request.headers.forEach(connection::setRequestProperty);
        active.add(connection);
        // Whoever sets this first owns the outcome: the exchange hands over its response, or the caller gives up.
        final AtomicBoolean settled = new AtomicBoolean();
        final Future<HttpResponse<T>> exchange;
        try {
            exchange = exchanges.submit(() -> exchange(connection, handler, settled));
        } catch (RejectedExecutionException ex) {
            disconnect(connection); throw new IOException("HTTP transport is closed", ex);
        }
        try {
            return exchange.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            if (settled.compareAndSet(false, true)) { abandon(connection); throw new HttpTimeoutException("HTTP request timed out"); }
            return handedOver(exchange);
        } catch (InterruptedException ex) {
            if (settled.compareAndSet(false, true)) abandon(connection);
            else close(handedOver(exchange));
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof SocketTimeoutException) throw new HttpTimeoutException("HTTP request timed out");
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException(cause);
        }
    }
    private <T> HttpResponse<T> exchange(HttpURLConnection connection, HttpResponse.BodyHandler<T> handler, AtomicBoolean settled)
            throws IOException {
        boolean handed = false;
        try {
            connection.connect();
            if (settled.get()) throw new HttpTimeoutException("HTTP request timed out");
            int status = connection.getResponseCode();
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            T body = handler.read(new Body(raw == null ? new ByteArrayInputStream(new byte[0]) : raw, connection));
            HttpResponse<T> response = new HttpResponse<>(status, body, connection.getHeaderFields());
            if (!settled.compareAndSet(false, true)) {
                close(response);
                throw new HttpTimeoutException("HTTP request timed out");
            }
            handed = true;
            return response;
        } finally {
            if (!handed) disconnect(connection);
        }
    }
    /** The exchange settled first, so its result is about to be set. */
    private static <T> HttpResponse<T> handedOver(Future<HttpResponse<T>> exchange) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try { return exchange.get(); }
                catch (InterruptedException ex) { interrupted = true; }
                catch (ExecutionException ex) { throw new IOException("HTTP request failed", ex.getCause()); }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static void close(HttpResponse<?> response) throws IOException {
        if (response.body() instanceof InputStream) ((InputStream) response.body()).close();
    }
    /** A disconnect can wait for a blocked read to return, so it never runs on the caller's thread. */
    private void abandon(HttpURLConnection connection) {
        try { exchanges.execute(() -> disconnect(connection)); }
        catch (RejectedExecutionException ignored) { /* close() disconnects every active connection */ }
    }
    private void disconnect(HttpURLConnection connection) {
        active.remove(connection);
        try { connection.disconnect(); } catch (RuntimeException ignored) { /* a concurrent disconnect already closed it */ }
    }
    /** Closing hands the connection back to the JDK, which keeps it for reuse when the body was read to the end. */
    private final class Body extends FilterInputStream {
        private final HttpURLConnection connection;
        Body(InputStream in, HttpURLConnection connection) { super(in); this.connection = connection; }
        @Override public int read() throws IOException { checkInterrupt(); return super.read(); }
        @Override public int read(byte[] bytes, int off, int len) throws IOException { checkInterrupt(); return in.read(bytes, off, len); }
        private void checkInterrupt() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("HTTP request interrupted");
        }
        @Override public void close() throws IOException { active.remove(connection); super.close(); }
    }
    /** Returns at once: disconnecting a stream that is being read waits for that read, for example a slow download. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        exchanges.shutdownNow();
        List<HttpURLConnection> open = new ArrayList<>(active);
        if (open.isEmpty()) return;
        Thread closer = new Thread(() -> open.forEach(this::disconnect), "OriginGate HTTP Close");
        closer.setDaemon(true);
        closer.start();
    }
}
