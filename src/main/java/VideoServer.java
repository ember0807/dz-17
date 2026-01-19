import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

public class VideoServer {
    private static final Path VIDEO_DIR = Paths.get("videos");
    private static final long MAX_FILE_SIZE = 1024L * 1024 * 1024; // 1 ГБ

    public static void main(String[] args) throws IOException {
        if (!Files.exists(VIDEO_DIR)) Files.createDirectories(VIDEO_DIR);

        // Создаем сервер на порту 8080
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", VideoServer::handleIndex);
        server.createContext("/upload", VideoServer::handleUpload);
        server.createContext("/video/", VideoServer::handleView);

        // Используем пул потоков, чтобы сервер мог обрабатывать несколько запросов сразу
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
        System.out.println("Сервер запущен: http://localhost:8080");
        server.start();
    }

    // --- ГЛАВНАЯ СТРАНИЦА ---
    private static void handleIndex(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            sendError(exchange, 404, "Страница не найдена");
            return;
        }

        List<String> files = Files.list(VIDEO_DIR)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".mp4") || name.endsWith(".webm"))
                .collect(Collectors.toList());

        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Video Server</title>");
        html.append("<style>body{font-family:sans-serif;margin:40px;} li{margin:10px 0;}</style></head><body>");
        html.append("<h1>Загруженные видео</h1><ul>");
        for (String file : files) {
            html.append("<li><a href='/video/").append(file).append("'>").append(file).append("</a></li>");
        }
        html.append("</ul><hr><h3>Загрузить новое видео (до 1 ГБ)</h3>");
        html.append("<form action='/upload' method='post' enctype='multipart/form-data'>");
        html.append("<input type='file' name='file' accept='.mp4,.webm'><br><br>");
        html.append("<input type='submit' value='Начать загрузку'>");
        html.append("</form></body></html>");

        byte[] response = html.toString().getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, 200, "text/html; charset=UTF-8", response);
    }

    // --- ЗАГРУЗКА ---
    private static void handleUpload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Метод не разрешен");
            return;
        }

        // Проверка размера файла через заголовок
        String contentLengthStr = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthStr != null && Long.parseLong(contentLengthStr) > MAX_FILE_SIZE) {
            sendError(exchange, 413, "Файл слишком большой (макс. 1 ГБ)");
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            // Читаем начало запроса, чтобы найти имя файла
            //пустая строка стандартный разделитель по этому ищем ее что бы отменить служебную инфу от видео
            ByteArrayOutputStream headerCollector = new ByteArrayOutputStream();
            int b;
            int lastFour = 0;
            while ((b = is.read()) != -1) {
                headerCollector.write(b);
                lastFour = (lastFour << 8) | (b & 0xFF);
                if (lastFour == 0x0D0A0D0A) break; // Нашли \r\n\r\n (конец заголовков)
            }

            String headers = headerCollector.toString(StandardCharsets.UTF_8);
            String fileName = "video_" + System.currentTimeMillis() + ".mp4";
            if (headers.contains("filename=\"")) {
                fileName = headers.split("filename=\"")[1].split("\"")[0];
            }

            Path target = VIDEO_DIR.resolve(fileName);
            try (OutputStream fos = Files.newOutputStream(target)) {
                is.transferTo(fos); // Сохраняем остаток потока в файл
            }

            // Перенаправляем на главную
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(303, -1);
        }
    }

    // --- ПРОСМОТР ---
    private static void handleView(HttpExchange exchange) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
        String fileName = path.substring("/video/".length());
        Path videoPath = VIDEO_DIR.resolve(fileName).normalize();

        // Проверка безопасности и наличия файла
        if (!videoPath.startsWith(VIDEO_DIR) || !Files.exists(videoPath)) {
            sendError(exchange, 404, "Файл не найден");
            return;
        }

        long fileSize = Files.size(videoPath);
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");

        Headers respHeaders = exchange.getResponseHeaders();
        respHeaders.set("Content-Type", fileName.endsWith(".webm") ? "video/webm" : "video/mp4");
        respHeaders.set("Accept-Ranges", "bytes");

        if (rangeHeader == null) {
            // Обычный запрос без перемотки
            exchange.sendResponseHeaders(200, fileSize);
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(videoPath, os);
            }
        } else {
            // Запрос части контента (прокрутка)
            try {
                String range = rangeHeader.replace("bytes=", "");
                String[] parts = range.split("-");
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isEmpty())
                        ? Long.parseLong(parts[1])
                        : fileSize - 1;

                if (start >= fileSize) {
                    respHeaders.set("Content-Range", "bytes */" + fileSize);
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }

                long contentLength = end - start + 1;
                respHeaders.set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                exchange.sendResponseHeaders(206, contentLength);

                try (InputStream is = Files.newInputStream(videoPath);
                     OutputStream os = exchange.getResponseBody()) {
                    is.skipNBytes(start); // Пропускаем до нужного байта

                    byte[] buffer = new byte[64 * 1024];
                    long bytesToRead = contentLength;
                    while (bytesToRead > 0) {
                        int read = is.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead));
                        if (read == -1) break;
                        os.write(buffer, 0, read);
                        bytesToRead -= read;
                    }
                }
            } catch (Exception e) {
                sendError(exchange, 400, "Некорректный запрос диапазона");
            }
        }
    }

    // --- отправитель ---
    private static void sendResponse(HttpExchange exchange, int code, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }
    // --- ошибки ---
    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, code, "text/plain; charset=UTF-8", data);
    }
}

