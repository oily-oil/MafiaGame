public class MessageCodec {

    // ===== 서버 → 클라이언트 수신 메시지 파싱 =====
    public static Message parseServerToClient(String line) {
        if (line == null) {
            return new Message(MessageType.UNKNOWN, "", "");
        }
        String raw = line;
        String msg = line.trim();

        // 순서 중요: 더 구체적인 것부터
        if (msg.startsWith("PLAYER_NUM:")) {
            return new Message(MessageType.PLAYER_NUM, raw,
                    msg.substring("PLAYER_NUM:".length()).trim());
        }
        if (msg.startsWith("TIMER:")) {
            return new Message(MessageType.TIMER, raw,
                    msg.substring("TIMER:".length()).trim());
        }
        if (msg.startsWith("PLAYERS_LIST:")) {
            return new Message(MessageType.PLAYERS_LIST, raw,
                    msg.substring("PLAYERS_LIST:".length()));
        }
        if (msg.startsWith("SYSTEM:")) {
            return new Message(MessageType.SYSTEM, raw,
                    msg.substring("SYSTEM:".length()));
        }
        if (msg.startsWith("ROLE:")) {
            return new Message(MessageType.ROLE, raw,
                    msg.substring("ROLE:".length()));
        }
        if (msg.equals("YOU_DIED")) {
            return new Message(MessageType.YOU_DIED, raw, "");
        }
        if (msg.startsWith("GAME_OVER")) {
            return new Message(MessageType.GAME_OVER, raw,
                    msg.substring("GAME_OVER".length()));
        }
        if (msg.equals("START_GAME") || msg.startsWith("START_GAME")) {
            // 혹시 payload가 붙어와도 대비해서 startsWith
            return new Message(MessageType.START_GAME, raw,
                    msg.length() > "START_GAME".length()
                            ? msg.substring("START_GAME".length()).trim()
                            : "");
        }
        if (msg.startsWith("CHAT_MAFIA:")) {
            return new Message(MessageType.CHAT_MAFIA, raw,
                    msg.substring("CHAT_MAFIA:".length()));
        }
        if (msg.startsWith("CHAT_DEAD:")) {
            return new Message(MessageType.CHAT_DEAD, raw,
                    msg.substring("CHAT_DEAD:".length()));
        }
        if (msg.startsWith("CHAT:")) {
            return new Message(MessageType.CHAT, raw,
                    msg.substring("CHAT:".length()));
        }
        if (msg.startsWith("MARK_TARGET:")) {
            return new Message(MessageType.MARK_TARGET, raw,
                    msg.substring("MARK_TARGET:".length()));
        }
        if (msg.startsWith("MARK_ROLE:")) {
            return new Message(MessageType.MARK_ROLE, raw,
                    msg.substring("MARK_ROLE:".length()));
        }

        return new Message(MessageType.UNKNOWN, raw, msg);
    }

    // ===== 클라이언트 → 서버 송신 메시지 파싱 =====
    public static Message parseClientToServer(String line) {
        if (line == null) {
            return new Message(MessageType.UNKNOWN, "", "");
        }
        String raw = line;
        String msg = line.trim();

        if (msg.equalsIgnoreCase("/start")) {
            return new Message(MessageType.COMMAND_START, raw, "");
        }
        if (msg.equalsIgnoreCase("/ready")) {
            return new Message(MessageType.COMMAND_READY, raw, "");
        }
        if (msg.startsWith("/skill ")) {
            String payload = msg.substring("/skill ".length()).trim();
            return new Message(MessageType.COMMAND_SKILL, raw, payload);
        }
        if (msg.startsWith("/vote ")) {
            String payload = msg.substring("/vote ".length()).trim();
            return new Message(MessageType.COMMAND_VOTE, raw, payload);
        }

        if (msg.startsWith("CHAT_MAFIA:")) {
            return new Message(MessageType.CHAT_MAFIA, raw,
                    msg.substring("CHAT_MAFIA:".length()));
        }
        if (msg.startsWith("CHAT_DEAD:")) {
            return new Message(MessageType.CHAT_DEAD, raw,
                    msg.substring("CHAT_DEAD:".length()));
        }
        if (msg.startsWith("CHAT:")) {
            return new Message(MessageType.CHAT, raw,
                    msg.substring("CHAT:".length()));
        }

        return new Message(MessageType.UNKNOWN, raw, msg);
    }

    // (필요하다면 나중에 encode 메서드도 여기 추가 가능)
}
