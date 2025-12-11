package mafia.server.protocol;

import mafia.Enum.GamePhase;
import mafia.Enum.MessageType;
import mafia.Enum.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 문자열 <-> Message 변환을 담당하는 프로토콜 유틸.
 *
 *  - parseServerToClient : 서버 → 클라 방향 문자열 파싱
 *  - parseClientToServer : 클라 → 서버 방향 문자열 파싱
 *
 * 실제 전송되는 문자열 포맷은 기존과 동일하게 유지:
 *  TIMER:DAY:55
 *  PLAYERS_LIST:P1 - ...,...
 *  CHAT:닉:메시지
 *  /ready, /start, /vote 2, /skill 3, /rooms, /join Room1 ...
 */
public class MessageCodec {

    private static final String PREFIX_TIMER         = "TIMER:";
    private static final String PREFIX_PLAYERS_LIST  = "PLAYERS_LIST:";
    private static final String PREFIX_SYSTEM        = "SYSTEM:";
    private static final String PREFIX_CHAT          = "CHAT:";
    private static final String PREFIX_CHAT_MAFIA    = "CHAT_MAFIA:";
    private static final String PREFIX_CHAT_DEAD     = "CHAT_DEAD:";
    private static final String PREFIX_MARK_TARGET   = "MARK_TARGET:";
    private static final String PREFIX_MARK_ROLE     = "MARK_ROLE:";
    private static final String PREFIX_PLAYER_NUM    = "PLAYER_NUM:";
    private static final String PREFIX_ROLE          = "ROLE:"; // 레거시

    // 새로 추가: 방 목록 전용 프리픽스
    private static final String PREFIX_ROOM_LIST     = "ROOM_LIST:";

    // ===================== 서버 → 클라이언트 =====================

    public static Message parseServerToClient(String line) {
        if (line == null) {
            return new Message(MessageType.UNKNOWN, "", null, null, null, null, null, null, null, null);
        }
        final String raw = line;
        line = line.trim();

        if (line.startsWith(PREFIX_PLAYER_NUM)) {
            try {
                int num = Integer.parseInt(line.substring(PREFIX_PLAYER_NUM.length()).trim());
                return Message.playerNum(raw, num);
            } catch (NumberFormatException e) {
                return Message.simple(MessageType.UNKNOWN, raw, line);
            }
        }

        if (line.startsWith(PREFIX_TIMER)) {
            String content = line.substring(PREFIX_TIMER.length());
            String[] parts = content.split(":");
            if (parts.length == 2) {
                try {
                    GamePhase phase = GamePhase.valueOf(parts[0]);
                    int sec = Integer.parseInt(parts[1]);
                    return Message.timer(raw, phase, sec);
                } catch (Exception e) {
                    return Message.simple(MessageType.UNKNOWN, raw, line);
                }
            }
            return Message.simple(MessageType.UNKNOWN, raw, line);
        }

        if (line.startsWith(PREFIX_PLAYERS_LIST)) {
            String listStr = line.substring(PREFIX_PLAYERS_LIST.length());
            List<String> players = new ArrayList<>();
            if (!listStr.isEmpty()) {
                players.addAll(Arrays.asList(listStr.split(",")));
            }
            return Message.playersList(raw, players);
        }

        // 🔹 방 목록: ROOM_LIST:Lobby (2명),Room1 (3명)
        //   → SYSTEM 메시지로 감싸서 Client.handleSystemMessage 의
        //     [ROOM_LIST] 처리 로직을 그대로 재사용
        if (line.startsWith(PREFIX_ROOM_LIST)) {
            String payload = line.substring(PREFIX_ROOM_LIST.length()).trim(); // "Lobby (2명),Room1 (3명)"
            String content = "[ROOM_LIST] " + payload;
            return Message.simple(MessageType.SYSTEM, raw, content);
        }

        if (line.startsWith("START_GAME")) {
            return Message.simple(MessageType.START_GAME, raw, null);
        }

        if (line.equals("YOU_DIED")) {
            return Message.simple(MessageType.YOU_DIED, raw, null);
        }

        if (line.startsWith("GAME_OVER")) {
            String content = line.substring("GAME_OVER".length()).trim();
            return Message.simple(MessageType.GAME_OVER, raw, content);
        }

        if (line.startsWith(PREFIX_SYSTEM)) {
            String content = line.substring(PREFIX_SYSTEM.length());
            return Message.simple(MessageType.SYSTEM, raw, content);
        }

        if (line.startsWith(PREFIX_CHAT_DEAD)) {
            return decodeChat(raw, line, PREFIX_CHAT_DEAD, MessageType.CHAT_DEAD);
        }

        if (line.startsWith(PREFIX_CHAT_MAFIA)) {
            return decodeChat(raw, line, PREFIX_CHAT_MAFIA, MessageType.CHAT_MAFIA);
        }

        if (line.startsWith(PREFIX_CHAT)) {
            return decodeChat(raw, line, PREFIX_CHAT, MessageType.CHAT);
        }

        if (line.startsWith(PREFIX_MARK_TARGET)) {
            String data = line.substring(PREFIX_MARK_TARGET.length()).trim(); // e.g. P3
            int num = parsePlayerNumber(data);
            return Message.markTarget(raw, num);
        }

        if (line.startsWith(PREFIX_MARK_ROLE)) {
            String data = line.substring(PREFIX_MARK_ROLE.length()); // e.g. P3:MAFIA
            String[] parts = data.split(":");
            if (parts.length == 2) {
                int num = parsePlayerNumber(parts[0].trim());
                Role role = parseRole(parts[1].trim());
                return Message.markRole(raw, num, role);
            }
            return Message.simple(MessageType.UNKNOWN, raw, line);
        }

        if (line.startsWith(PREFIX_ROLE)) {
            String content = line.substring(PREFIX_ROLE.length()).trim();
            return Message.simple(MessageType.ROLE_INFO, raw, content);
        }

        return Message.simple(MessageType.UNKNOWN, raw, line);
    }

    private static Message decodeChat(String raw, String line, String prefix, MessageType type) {
        String content = line.substring(prefix.length()).trim(); // "nick:hello"
        String sender = null;
        String msg = content;
        int idx = content.indexOf(':');
        if (idx > 0) {
            sender = content.substring(0, idx).trim();
            msg = content.substring(idx + 1).trim();
        }
        return Message.chat(type, raw, sender, msg);
    }

    private static int parsePlayerNumber(String token) {
        String t = token.trim();
        if (t.startsWith("P") || t.startsWith("p")) {
            t = t.substring(1);
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Role parseRole(String s) {
        try {
            return Role.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Role.NONE;
        }
    }

    // ===================== 클라이언트 → 서버 =====================

    public static Message parseClientToServer(String line) {
        if (line == null) {
            return new Message(MessageType.UNKNOWN, "", null, null, null, null, null, null, null, null);
        }
        final String raw = line;
        line = line.trim();
        if (line.isEmpty()) {
            return new Message(MessageType.UNKNOWN, raw, null, null, null, null, null, null, null, null);
        }

        // 채팅
        if (line.startsWith(PREFIX_CHAT_DEAD)) {
            return decodeChat(raw, line, PREFIX_CHAT_DEAD, MessageType.CHAT_DEAD);
        }
        if (line.startsWith(PREFIX_CHAT_MAFIA)) {
            return decodeChat(raw, line, PREFIX_CHAT_MAFIA, MessageType.CHAT_MAFIA);
        }
        if (line.startsWith(PREFIX_CHAT)) {
            return decodeChat(raw, line, PREFIX_CHAT, MessageType.CHAT);
        }

        // 명령
        if (line.equalsIgnoreCase("/ready")) {
            return Message.command(MessageType.CMD_READY, raw, null, null);
        }
        if (line.equalsIgnoreCase("/start")) {
            return Message.command(MessageType.CMD_START, raw, null, null);
        }
        if (line.toLowerCase().startsWith("/vote ")) {
            String numStr = line.substring(6).trim();
            Integer num = safeInt(numStr);
            return Message.command(MessageType.CMD_VOTE, raw, num, null);
        }
        if (line.toLowerCase().startsWith("/skill ")) {
            String numStr = line.substring(7).trim();
            Integer num = safeInt(numStr);
            return Message.command(MessageType.CMD_SKILL, raw, num, null);
        }
        if (line.equalsIgnoreCase("/rooms")) {
            return Message.command(MessageType.CMD_ROOMS, raw, null, null);
        }
        if (line.toLowerCase().startsWith("/join ")) {
            String roomName = line.substring(6).trim();
            return Message.command(MessageType.CMD_JOIN, raw, null, roomName);
        }

        return Message.simple(MessageType.UNKNOWN, raw, line);
    }

    private static Integer safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }
}
