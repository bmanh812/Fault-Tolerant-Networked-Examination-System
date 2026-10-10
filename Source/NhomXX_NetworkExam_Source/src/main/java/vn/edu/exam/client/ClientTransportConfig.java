package vn.edu.exam.client;

import vn.edu.exam.common.config.AppConfig;

import java.util.Objects;

public record ClientTransportConfig(
        int connectTimeoutMs,
        int socketReadTimeoutMs,
        int requestTimeoutMs,
        int maxMessageBytes
) {
    public ClientTransportConfig {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeoutMs must be positive");
        }
        if (socketReadTimeoutMs < 0) {
            throw new IllegalArgumentException("socketReadTimeoutMs must be zero or positive");
        }
        if (requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("requestTimeoutMs must be positive");
        }
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
    }

    public static ClientTransportConfig from(AppConfig config) {
        Objects.requireNonNull(config, "config is required");
        return new ClientTransportConfig(
                config.getInt("client.connectTimeoutMs"),
                config.getInt("client.socketReadTimeoutMs"),
                config.getInt("client.requestTimeoutMs"),
                config.getInt("protocol.maxMessageBytes")
        );
    }
}
