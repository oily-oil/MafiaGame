public class Message {
    private final MessageType type;
    private final String raw;      // 전체 원본 문자열
    private final String payload;  // 접두어 제거 후 남은 부분

    public Message(MessageType type, String raw, String payload) {
        this.type = type;
        this.raw = raw;
        this.payload = payload;
    }

    public MessageType getType() {
        return type;
    }

    public String getRaw() {
        return raw;
    }

    public String getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", raw='" + raw + '\'' +
                ", payload='" + payload + '\'' +
                '}';
    }
}
