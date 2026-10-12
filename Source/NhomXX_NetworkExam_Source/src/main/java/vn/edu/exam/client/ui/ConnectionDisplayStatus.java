package vn.edu.exam.client.ui;

public enum ConnectionDisplayStatus {
    CONNECTING("Đang kết nối..."),
    CONNECTED("Đã kết nối"),
    DISCONNECTED("Mất kết nối");

    private final String label;

    ConnectionDisplayStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
