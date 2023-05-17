package org.apache.hc.client5.http;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;


import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.impl.bootstrap.HttpServer;
import org.apache.hc.core5.http.impl.bootstrap.ServerBootstrap;
import org.apache.hc.core5.http.io.entity.StringEntity;


public class Server {

    private static final Logger logger = Logger.getLogger(Server.class.getName());

    public static void main(String[] args) throws Exception {
        final URI uri = new URI("your/path/to/files");

        final HttpServer server = ServerBootstrap.bootstrap()
                .register("*", (request, response, context) -> {
                        String path = request.getPath();
                        Path filePath = Paths.get(uri.getPath(), path);
                        byte[] fileContent;

                        try {
                            fileContent = Files.readAllBytes(filePath);
                            response.setHeader("Cache-Control", "public, max-age=3600");  // cache for 1 hour
                            response.setEntity(new StringEntity(new String(fileContent), ContentType.TEXT_PLAIN));
                        } catch (IOException e) {
                            logger.severe("Error: " + e.getMessage());
                            StringEntity entity = new StringEntity("Internal server error", ContentType.TEXT_PLAIN);
                            response.setCode(500);
                            response.setEntity(entity);
                        }

                })
                .setListenerPort(8000)
                .create();

        server.start();
        logger.info("Server started on port 8000");
    }



     /*

     If you want to use HttpExchange instead of HttpCore, you can use this code:



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
                Path filePath = Paths.get("your/path/to/files", requestURI);

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
    }*/
}