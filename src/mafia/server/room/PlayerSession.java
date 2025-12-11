package mafia.server.room;

import mafia.Enum.PlayerStatus;
import mafia.Enum.Role;
import mafia.server.ClientHandler;

public class PlayerSession {

    private final int playerNumber;
    private String name;

    private Role role = Role.NONE;
    private PlayerStatus status = PlayerStatus.ALIVE;

    private boolean host = false;
    private boolean ready = false;

    private ClientHandler handler;

    public PlayerSession(int playerNumber, String name) {
        this.playerNumber = playerNumber;
        this.name = name;
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
        this.role = role;
    }

    public PlayerStatus getStatus() {
        return status;
    }

    public void setStatus(PlayerStatus status) {
        this.status = status;
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

    public ClientHandler getHandler() {
        return handler;
    }

    public void setHandler(ClientHandler handler) {
        this.handler = handler;
    }

    public void send(String msg) {
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }

    public String getDisplayName() {
        return name + "(P" + playerNumber + ")";
    }
}
