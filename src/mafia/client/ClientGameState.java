package mafia.client;

import mafia.Enum.GamePhase;
import mafia.Enum.Role;

import java.util.HashMap;
import java.util.Map;

/**
 * 클라이언트 쪽에서 "게임 상태"만 관리하는 순수 도메인 클래스.
 * - 네트워크/GUI는 알지 못함.
 */
public class ClientGameState {

    private boolean inGame = false;
    private boolean alive = true;

    private Role myRole = Role.NONE;
    private String myNickname = "";
    private int myPlayerNumber = 0;

    private boolean host = false;
    private boolean ready = false;

    // 서버에서 MARK_TARGET:P2 등으로 내려주는 지목 정보
    private String markedPlayer = "";

    // POLICE 조사 결과 표시용 (key: "P2", value: "MAFIA" or "CITIZEN")
    private final Map<String, String> investigatedRoles = new HashMap<>();

    private GamePhase currentPhase = GamePhase.WAITING;

    public void resetForNewConnection(String nickname) {
        this.myNickname = nickname != null ? nickname : "";
        this.inGame = false;
        this.alive = true;
        this.myRole = Role.NONE;
        this.myPlayerNumber = 0;
        this.host = false;
        this.ready = false;
        this.markedPlayer = "";
        this.investigatedRoles.clear();
        this.currentPhase = GamePhase.WAITING;
    }

    public void resetForLobbyAfterGame(boolean wasHost) {
        this.inGame = false;
        this.alive = true;
        this.myRole = Role.NONE;
        this.markedPlayer = "";
        this.investigatedRoles.clear();
        this.myPlayerNumber = 0;
        this.host = wasHost;
        this.ready = wasHost;
        this.currentPhase = GamePhase.WAITING;
    }

    public boolean hasAbility() {
        return myRole == Role.MAFIA || myRole == Role.POLICE || myRole == Role.DOCTOR;
    }

    // ======= Getter / Setter =======

    public boolean isInGame() {
        return inGame;
    }

    public void setInGame(boolean inGame) {
        this.inGame = inGame;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public Role getMyRole() {
        return myRole;
    }

    public void setMyRole(Role myRole) {
        this.myRole = myRole != null ? myRole : Role.NONE;
    }

    public String getMyNickname() {
        return myNickname;
    }

    public void setMyNickname(String myNickname) {
        this.myNickname = myNickname;
    }

    public int getMyPlayerNumber() {
        return myPlayerNumber;
    }

    public void setMyPlayerNumber(int myPlayerNumber) {
        this.myPlayerNumber = myPlayerNumber;
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

    public String getMarkedPlayer() {
        return markedPlayer;
    }

    public void setMarkedPlayer(String markedPlayer) {
        this.markedPlayer = markedPlayer != null ? markedPlayer : "";
    }

    public Map<String, String> getInvestigatedRoles() {
        return investigatedRoles;
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(GamePhase currentPhase) {
        this.currentPhase = currentPhase != null ? currentPhase : GamePhase.WAITING;
    }
}
