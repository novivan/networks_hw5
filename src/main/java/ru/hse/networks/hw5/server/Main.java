package ru.hse.networks.hw5.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("Invalid port '{}', using {}", args[0], port);
            }
        }
        log.info("Starting chat server on port {}", port);
        new ChatServer(port).run();
    }
}
