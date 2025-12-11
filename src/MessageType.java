public enum MessageType {
    // 서버 → 클라이언트
    PLAYER_NUM,
    TIMER,
    PLAYERS_LIST,
    START_GAME,
    ROLE,
    SYSTEM,
    YOU_DIED,
    GAME_OVER,
    CHAT,
    CHAT_MAFIA,
    CHAT_DEAD,
    MARK_TARGET,
    MARK_ROLE,

    // 클라이언트 → 서버 명령
    COMMAND_START,
    COMMAND_READY,
    COMMAND_SKILL,
    COMMAND_VOTE,

    // 그 외 알 수 없는 메시지
    UNKNOWN
}
