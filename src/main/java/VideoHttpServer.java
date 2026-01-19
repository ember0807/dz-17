import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class VideoHttpServer {

    private static final Path STATIC_FILES_PATH = Path.of("static");

    private static final int CHUNK_SIZE = 128 * 1024 * 1024;

    static void main() throws IOException {
        Files.createDirectories(STATIC_FILES_PATH);

        InetSocketAddress socketAddress = new InetSocketAddress(80);
        HttpServer httpServer = HttpServer.create(socketAddress, 0);

        httpServer.createContext("/", (exchange) -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            System.out.println(method);
            System.out.println(path);

            if (method.equalsIgnoreCase("get") && path.equalsIgnoreCase("/video/player"))
                handleVideoPlayer(exchange);
            else if (method.equalsIgnoreCase("get") && path.equalsIgnoreCase("/video"))
                handleVideo(exchange);
        });

        httpServer.start();
    }

    private static void handleVideoPlayer(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders()
                .add("Content-Type", "text/html; charset=UTF-8");

        exchange.sendResponseHeaders(200, 0);

        Path htmlFilePath = STATIC_FILES_PATH.resolve("video-player.html");

        try (OutputStream outputStream = exchange.getResponseBody();
             InputStream inputStream = Files.newInputStream(htmlFilePath)) {

            inputStream.transferTo(outputStream);
        }
    }

    private static void handleVideo(HttpExchange exchange) throws IOException {
        Path videoFilePath = STATIC_FILES_PATH.resolve("video.mp4");
        long videoFileSize = Files.size(videoFilePath);

        // bytes=0- ИЛИ bytes=1024-2048
        String rangeValue = exchange.getRequestHeaders().getFirst("Range");

        // ["0"] ИЛИ ["1024", "2048"]
        String[] ranges = rangeValue.substring(6).split("-");

        long from = Long.parseLong(ranges[0].trim());

        long to = (ranges.length > 1 && !ranges[1].isBlank())
                ? Long.parseLong(ranges[1].trim())
                : videoFileSize - 1;

        long contentLength = to - from + 1;

        Headers responseHeaders = exchange.getResponseHeaders();

        responseHeaders.set("Content-Type", "video/mp4");
        responseHeaders.set("Accept-Ranges", "bytes");
        responseHeaders.set("Content-Range", "bytes " + from + "-" + to + "/" + videoFileSize);

        // Request: byte=0-
        // Response: bytes 0-1023/1024

        exchange.sendResponseHeaders(206, contentLength);

        try (InputStream inputStream = Files.newInputStream(videoFilePath);
             OutputStream outputStream = exchange.getResponseBody()) {

            byte[] buffer = new byte[CHUNK_SIZE];

            inputStream.skipNBytes(from);

            long bytesLeft = contentLength;
            int bytesRead;

            while (bytesLeft > 0) {
                bytesRead = inputStream.read(
                        buffer,
                        0,
                        (int) Math.min(CHUNK_SIZE, bytesLeft)
                );

                if (bytesRead == -1)
                    break;

                outputStream.write(buffer, 0, bytesRead);
                bytesLeft -= bytesRead;
            }
        }
    }
}