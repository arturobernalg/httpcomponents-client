package org.apache.hc.client5.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/assert", new StaticFileHandler());
        server.setExecutor(null); // creates a default executor
        server.start();
        logger.info("Server started on port 8000");
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            try {
                String requestURI = httpExchange.getRequestURI().toString();
                Path filePath = Paths.get("/Users/abernal/IdeaProyect/httpcomponents-client/httpclient5-cache/src/test/java/org/apache/hc/client5/http/", requestURI);

                byte[] fileContent = Files.readAllBytes(filePath);
                httpExchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");  // cache for 1 hour
                httpExchange.sendResponseHeaders(200, fileContent.length); // this should be called after setting all headers
                OutputStream outputStream = httpExchange.getResponseBody();
                outputStream.write(fileContent);
                outputStream.close();
            } catch (IOException e) {
                logger.severe("Error: " + e.getMessage());
                String response = "Internal server error";
                httpExchange.sendResponseHeaders(500, response.getBytes().length);
                OutputStream os = httpExchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }
}