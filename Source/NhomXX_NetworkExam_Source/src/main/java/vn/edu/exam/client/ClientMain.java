package vn.edu.exam.client;

import vn.edu.exam.common.config.AppConfig;
import vn.edu.exam.common.protocol.MessageType;
import vn.edu.exam.common.protocol.ProtocolIO;
import vn.edu.exam.common.protocol.ProtocolMessage;

import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;

public final class ClientMain {
    private ClientMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/application.properties");
        AppConfig config = AppConfig.load(configPath);
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = config.getInt("server.port");

        try (Socket socket = new Socket(host, port)) {
            ProtocolIO protocol = new ProtocolIO(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    config.getInt("protocol.maxFrameBytes")
            );
            protocol.write(ProtocolMessage.request(MessageType.PING, Map.of("client", "cli-skeleton")));
            ProtocolMessage response = protocol.read();
            System.out.println("Server response: " + response);
        }
    }
}

