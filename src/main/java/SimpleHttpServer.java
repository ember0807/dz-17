import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public class SimpleHttpServer {
    private static final Path STATIC_ROOT = Path.of("./static").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        if (Files.notExists(STATIC_ROOT)) Files.createDirectories(STATIC_ROOT);

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (method.equalsIgnoreCase("GET")) {
                handleGet(exchange, path);
            } else if (method.equalsIgnoreCase("POST") && path.equals("/upload")) {
                handleUpload(exchange);
            } else {
                sendError(exchange, 405, "Метод не поддерживается");
            }
        });

        System.out.println("Сервер запущен: http://localhost:8080");
        server.start();
    }

    // --- ОБРАБОТКА GET (Просмотр файлов) ---
    private static void handleGet(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) {
            sendIndexPage(exchange);
            return;
        }
        // Формируем полный путь к файлу:
        // 1. Удаляем первый символ "/" из path с помощью substring(1)
        // 2. Разрешаем путь относительно STATIC_ROOT
        // 3. Нормализуем путь (убираем ".." и "." для безопасности)
        Path filePath = STATIC_ROOT.resolve(path.substring(1)).normalize();
        if (filePath.startsWith(STATIC_ROOT) && Files.isRegularFile(filePath)) {
            byte[] data = Files.readAllBytes(filePath);
            sendResponse(exchange, 200, data, Map.of("Content-Type", getMimeType(filePath)));
        } else {
            sendError(exchange, 404, "Файл не найден");
        }
    }

    // --- ОБРАБОТКА POST (Загрузка файла) ---
    private static void handleUpload(HttpExchange exchange) throws IOException {
        // сохраняем всё тело запроса как один файл.

        String fileName = exchange.getRequestHeaders().getFirst("File-Name");
        if (fileName == null) fileName = "uploaded_file_" + System.currentTimeMillis() + ".dat";

        try (InputStream is = exchange.getRequestBody();
             OutputStream os = Files.newOutputStream(STATIC_ROOT.resolve(fileName))) {
            is.transferTo(os);
        }

        // Перенаправляем обратно на главную
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
    }

    private static void sendIndexPage(HttpExchange exchange) throws IOException {
        String fileList = Files.list(STATIC_ROOT)
                .map(p -> String.format("<li><a href=\"/%s\">%s</a></li>", p.getFileName(), p.getFileName()))
                .collect(Collectors.joining());

        String html = """
            <html>
            <head><meta charset="UTF-8"><title>Файловый менеджер</title></head>
            <body style="font-family: sans-serif; padding: 20px;">
                <h2>Загрузить новый файл</h2>
                <input type="file" id="picker">
                <button onclick="upload()">Отправить на сервер</button>
                
                <hr>
                <h2>Список файлов в ./static/</h2>
                <ul> %s </ul>

                <script>
                    async function upload() {
                        const file = document.getElementById('picker').files[0];
                        if (!file) return alert('Выберите файл!');
                        
                        await fetch('/upload', {
                            method: 'POST',
                            headers: { 'File-Name': encodeURIComponent(file.name) },
                            body: file});
                        location.reload();
                    }
                </script>
            </body>
            </html>
            """.formatted(fileList);

        sendResponse(exchange, 200, html.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "text/html; charset=UTF-8"));
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ---
    private static String getMimeType(Path path) {
        String name = path.toString().toLowerCase();
        if (name.endsWith(".txt")) {
            // по идее кодировка должна работать но что то идет не так, не понимаю
            return "text/plain; charset=UTF-8";
        }
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static void sendResponse(HttpExchange e, int code, byte[] d, Map<String, String> h) throws IOException {
        h.forEach((k, v) -> e.getResponseHeaders().set(k, v));
        e.sendResponseHeaders(code, d.length);
        try (OutputStream os = e.getResponseBody()) { os.write(d); }
    }

    private static void sendError(HttpExchange e, int c, String m) throws IOException {
        sendResponse(e, c, m.getBytes(), Map.of("Content-Type", "text/plain"));
    }
}