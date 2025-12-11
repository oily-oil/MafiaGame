package mafia.server.room;

import mafia.Enum.PlayerStatus;
import mafia.Enum.Role;
import mafia.server.ClientHandler;

/**
 * 서버 내 "한 플레이어"를 표현하는 도메인 클래스.
 *  - 상태(Role, ALIVE/DEAD, HOST/READY 등)을 보관
 *  - 실제 네트워크 전송은 내부의 ClientHandler를 통해 수행
 */
public class PlayerSession {

    private final ClientHandler handler;
    private final int playerNumber;

    private String name;
    private Role role = Role.NONE;
    private PlayerStatus status = PlayerStatus.ALIVE;
    private boolean host = false;
    private boolean ready = false;

    public PlayerSession(ClientHandler handler, int playerNumber, String name) {
        this.handler = handler;
        this.playerNumber = playerNumber;
        this.name = name;
    }

    public void send(String message) {
        handler.sendMessage(message);
    }

    public ClientHandler getHandler() {
        return handler;
    }

    public int getPlayerNumber() {
        return playerNumber;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role != null ? role : Role.NONE;
    }

    public PlayerStatus getStatus() {
        return status;
    }

    public void setStatus(PlayerStatus status) {
        this.status = status != null ? status : PlayerStatus.ALIVE;
    }

    public boolean isHost() {
        return host;
    }

    public void setHost(boolean host) {
        this.host = host;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
