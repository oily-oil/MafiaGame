package mafia.server.protocol;

import mafia.Enum.GamePhase;
import mafia.Enum.MessageType;
import mafia.Enum.Role;

import java.util.Collections;
import java.util.List;

/**
 * 양방향 공용 메시지 도메인 클래스.
 *  - type: 메시지 종류
 *  - raw : 실제 전송된 한 줄 문자열 (원본)
 *  - text: SYSTEM, GAME_OVER, 일반 텍스트 등
 *  - sender: 채팅 보낸 사람 닉네임
 *  - playerNumber: 대상 플레이어 번호 (P3 → 3)
 *  - seconds: 타이머 초
 *  - phase: 게임 단계 (WAITING/DAY/NIGHT)
 *  - role: 역할 정보 (MARK_ROLE, ROLE_INFO 등)
 *  - players: PLAYERS_LIST 에서 받은 플레이어 문자열 목록
 *  - roomName: /join RoomName 에서 방 이름
 */
public class Message {

    private final MessageType type;
    private final String raw;

    private final String text;
    private final String sender;
    private final Integer playerNumber;
    private final Integer seconds;
    private final GamePhase phase;
    private final Role role;
    private final List<String> players;
    private final String roomName;

    public Message(MessageType type,
                   String raw,
                   String text,
                   String sender,
                   Integer playerNumber,
                   Integer seconds,
                   GamePhase phase,
                   Role role,
                   List<String> players,
                   String roomName) {
        this.type = type;
        this.raw = raw;
        this.text = text;
        this.sender = sender;
        this.playerNumber = playerNumber;
        this.seconds = seconds;
        this.phase = phase;
        this.role = role;
        this.players = (players == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(players);
        this.roomName = roomName;
    }

    // ---- 편의 생성자들 ----
    public static Message simple(MessageType type, String raw, String text) {
        return new Message(type, raw, text, null, null, null, null, null, null, null);
    }

    public static Message chat(MessageType type, String raw, String sender, String text) {
        return new Message(type, raw, text, sender, null, null, null, null, null, null);
    }

    public static Message timer(String raw, GamePhase phase, int seconds) {
        return new Message(MessageType.TIMER, raw, null, null, null, seconds, phase, null, null, null);
    }

    public static Message playerNum(String raw, int num) {
        return new Message(MessageType.PLAYER_NUM, raw, null, null, num, null, null, null, null, null);
    }

    public static Message playersList(String raw, List<String> players) {
        return new Message(MessageType.PLAYERS_LIST, raw, null, null, null, null, null, null, players, null);
    }

    public static Message markTarget(String raw, int playerNumber) {
        return new Message(MessageType.MARK_TARGET, raw, null, null, playerNumber, null, null, null, null, null);
    }

    public static Message markRole(String raw, int playerNumber, Role role) {
        return new Message(MessageType.MARK_ROLE, raw, null, null, playerNumber, null, null, role, null, null);
    }

    public static Message command(MessageType type, String raw, Integer playerNumber, String roomName) {
        return new Message(type, raw, null, null, playerNumber, null, null, null, null, roomName);
    }

    // ---- getters ----
    public MessageType getType() { return type; }
    public String getRaw() { return raw; }
    public String getText() { return text; }
    public String getSender() { return sender; }
    public Integer getPlayerNumber() { return playerNumber; }
    public Integer getSeconds() { return seconds; }
    public GamePhase getPhase() { return phase; }
    public Role getRole() { return role; }
    public List<String> getPlayers() { return players; }
    public String getRoomName() { return roomName; }
}
