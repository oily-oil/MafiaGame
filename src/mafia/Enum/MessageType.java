package mafia.Enum;

/**
 * 통신 프로토콜에서 사용하는 메시지 타입 정의
 *  - 서버 → 클라이언트
 *  - 클라이언트 → 서버 (CMD_...)
 */
public enum MessageType {

    // ===== 서버 → 클라이언트 =====
    PLAYER_NUM,
    TIMER,
    PLAYERS_LIST,
    START_GAME,
    ROLE_INFO,
    YOU_DIED,
    GAME_OVER,
    SYSTEM,
    CHAT,
    CHAT_MAFIA,
    CHAT_DEAD,
    MARK_TARGET,
    MARK_ROLE,

    // ===== 클라이언트 → 서버 =====
    CMD_READY,
    CMD_START,
    CMD_VOTE,
    CMD_SKILL,
    CMD_ROOMS,
    CMD_JOIN,

    // 그 외 알 수 없는 문자열
    UNKNOWN
}
