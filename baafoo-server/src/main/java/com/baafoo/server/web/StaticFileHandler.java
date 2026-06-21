package com.baafoo.server.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Static file server for Baafoo Web Console (SPA).
 *
 * <p>Serves files from the configured webConsolePath (filesystem) or from
 * the classpath ({@code webconsole/} inside the JAR). Classpath loading
 * enables single-JAR deployment where the frontend is packaged alongside
 * the server.</p>
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If {@code webConsolePath} is set and the file exists on disk → serve from filesystem</li>
 *   <li>Otherwise → serve from classpath {@code webconsole/}</li>
 * </ol></p>
 *
 * <p>Implements SPA fallback: non-file requests serve index.html.
 * Path prefix: /__baafoo__/</p>
 */
public class StaticFileHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(StaticFileHandler.class);

    private static final String SPA_PREFIX = "/__baafoo__/";
    private static final String INDEX_HTML = "index.html";
    private static final String CLASSPATH_PREFIX = "webconsole";

    private final String webRoot;
    private final boolean filesystemMode;
    private final Map<String, String> mimeTypes;

    public StaticFileHandler(String webRoot) {
        this.mimeTypes = new HashMap<String, String>();
        initMimeTypes();

        // Determine serving mode: filesystem if webRoot is explicitly set and exists,
        // otherwise fall back to classpath
        if (webRoot != null && !webRoot.isEmpty()) {
            File dir = new File(webRoot);
            if (dir.isDirectory()) {
                this.webRoot = webRoot;
                this.filesystemMode = true;
                log.info("Web console: filesystem mode (root={})", webRoot);
                return;
            }
        }

        // Try classpath mode
        URL indexUrl = getClass().getClassLoader().getResource(CLASSPATH_PREFIX + "/index.html");
        if (indexUrl != null) {
            this.webRoot = null;
            this.filesystemMode = false;
            log.info("Web console: classpath mode (embedded in JAR)");
        } else {
            // Last resort: try ./web directory
            File fallback = new File("./web");
            this.webRoot = "./web";
            this.filesystemMode = fallback.isDirectory();
            log.warn("Web console: no embedded resources found, falling back to {} (exists={})",
                    this.webRoot, this.filesystemMode);
        }
    }

    private void initMimeTypes() {
        mimeTypes.put("html", "text/html; charset=UTF-8");
        mimeTypes.put("css", "text/css; charset=UTF-8");
        mimeTypes.put("js", "application/javascript; charset=UTF-8");
        mimeTypes.put("json", "application/json; charset=UTF-8");
        mimeTypes.put("png", "image/png");
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("jpeg", "image/jpeg");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("svg", "image/svg+xml");
        mimeTypes.put("ico", "image/x-icon");
        mimeTypes.put("woff", "font/woff");
        mimeTypes.put("woff2", "font/woff2");
        mimeTypes.put("ttf", "font/ttf");
        mimeTypes.put("eot", "application/vnd.ms-fontobject");
        mimeTypes.put("map", "application/json");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        String path = extractPath(uri);

        // Only handle /__baafoo__/ prefix
        if (!path.startsWith(SPA_PREFIX) && !path.equals("/") && !path.startsWith("/__baafoo__/api/")) {
            // Also serve static files under /assets/ directly
            if (path.startsWith("/assets/") || path.equals("/favicon.ico")) {
                serveStaticFile(ctx, path);
                return;
            }
            // 404 for unknown paths
            sendError(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
            return;
        }

        // Strip SPA prefix
        String filePath = path.startsWith(SPA_PREFIX)
                ? path.substring(SPA_PREFIX.length() - 1) // keep leading /
                : path;

        if (filePath.isEmpty() || filePath.equals("/")) {
            filePath = "/" + INDEX_HTML;
        }

        // Try to serve the file
        boolean served = serveStaticFile(ctx, filePath);
        if (!served) {
            // SPA fallback: serve index.html for all non-file routes
            if (!filePath.contains(".")) {
                serveStaticFile(ctx, "/" + INDEX_HTML);
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "File not found");
            }
        }
    }

    private boolean serveStaticFile(ChannelHandlerContext ctx, String path) {
        // Normalize and secure path — prevent path traversal
        String normalizedPath = path.replace("..", "").replace("//", "/");
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        // Defense-in-depth: reject any path that still contains traversal sequences
        // after normalization (e.g., encoded variants like ..%2F)
        if (normalizedPath.contains("..")) {
            log.warn("Path traversal attempt blocked: {}", path);
            return false;
        }

        if (filesystemMode) {
            // Canonicalize and verify the resolved file is still under webRoot
            try {
                File file = new File(webRoot, normalizedPath).getCanonicalFile();
                File root = new File(webRoot).getCanonicalFile();
                if (!file.getPath().startsWith(root.getPath())) {
                    log.warn("Path traversal attempt blocked: {}", path);
                    return false;
                }
            } catch (Exception e) {
                log.debug("Path canonicalization failed for {}: {}", path, e.getMessage());
                return false;
            }
            return serveFromFilesystem(ctx, normalizedPath);
        } else {
            return serveFromClasspath(ctx, normalizedPath);
        }
    }

    private boolean serveFromFilesystem(ChannelHandlerContext ctx, String normalizedPath) {
        try {
            File file = new File(webRoot, normalizedPath);

            if (!file.exists() || file.isHidden() || !file.isFile()) {
                return false;
            }

            String contentType = getContentType(file.getName());

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            long fileLength = raf.length();

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=3600");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

            ctx.write(response);
            ctx.write(new ChunkedFile(raf, 0, fileLength, 8192));
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);

            log.debug("Served from filesystem: {} ({} bytes)", normalizedPath, fileLength);
            return true;
        } catch (Exception e) {
            log.debug("Could not serve from filesystem {}: {}", normalizedPath, e.getMessage());
            return false;
        }
    }

    private boolean serveFromClasspath(ChannelHandlerContext ctx, String normalizedPath) {
        try {
            String resourcePath = CLASSPATH_PREFIX + "/" + normalizedPath;
            InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                return false;
            }

            try {
                byte[] bytes = readFully(is);
                String contentType = getContentType(normalizedPath);

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=3600");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

                log.debug("Served from classpath: {} ({} bytes)", normalizedPath, bytes.length);
                return true;
            } finally {
                is.close();
            }
        } catch (Exception e) {
            log.debug("Could not serve from classpath {}: {}", normalizedPath, e.getMessage());
            return false;
        }
    }

    private static byte[] readFully(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = is.read(buffer)) != -1) {
            baos.write(buffer, 0, n);
        }
        return baos.toByteArray();
    }

    private String getContentType(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0) return "application/octet-stream";
        String ext = fileName.substring(dotIdx + 1).toLowerCase();
        return mimeTypes.getOrDefault(ext, "application/octet-stream");
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        String body = "<html><body><h1>" + status.code() + " " + status.reasonPhrase()
                + "</h1><p>" + message + "</p></body></html>";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("StaticFileHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
