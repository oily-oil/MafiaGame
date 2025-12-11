package mafia.server.room;

import mafia.Enum.PlayerStatus;
import mafia.Enum.Role;
import mafia.Enum.GamePhase;
import mafia.server.GameServer;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class PlayerSession implements Runnable {

    private final GameServer server;
    private final Socket socket;

    private BufferedReader in;
    private PrintWriter out;

    private final int playerNumber;
    private String name;

    private Role role = Role.NONE;
    private PlayerStatus status = PlayerStatus.ALIVE;
    private boolean host = false;
    private boolean ready = false;

    private GameRoom currentRoom;

    public PlayerSession(GameServer server, Socket socket, int playerNumber) {
        this.server = server;
        this.socket = socket;
        this.playerNumber = playerNumber;
        this.name = "플레이어 " + playerNumber;
    }

    // ===== Getter / Setter =====

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

    public GameRoom getCurrentRoom() {
        return currentRoom;
    }

    public void setCurrentRoom(GameRoom currentRoom) {
        this.currentRoom = currentRoom;
    }

    public void send(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String line;
            while ((line = in.readLine()) != null) {
                String msg = line.trim();
                if (msg.isEmpty()) continue;

                // 닉네임 설정
                if (msg.startsWith("NICKNAME:")) {
                    String nick = msg.substring("NICKNAME:".length()).trim();
                    if (!nick.isEmpty()) {
                        this.name = nick;
                    }
                    continue;
                }

                if (currentRoom == null) {
                    send("SYSTEM:아직 어떤 방에도 속해있지 않습니다. /room create 또는 /room join 사용.");
                    continue;
                }

                // 사망자 제한
                if (status == PlayerStatus.DEAD &&
                        !msg.startsWith("CHAT_DEAD:") &&
                        !msg.equals("/ready")) {
                    send("SYSTEM:당신은 죽었습니다. 채팅 외의 행동은 할 수 없습니다.");
                    continue;
                }

                // ======= 명령 처리 =======

                if (msg.startsWith("/room ")) {
                    handleRoomCommand(msg.substring(6).trim());
                }
                else if (msg.equals("/start")) {
                    currentRoom.startGame(this);
                }
                else if (msg.equals("/ready")) {
                    currentRoom.handleReady(this);
                }
                else if (msg.startsWith("/vote ")) {
                    currentRoom.handleVote(this, msg);
                }
                else if (msg.startsWith("/skill ")) {
                    if (currentRoom.getCurrentPhase() != GamePhase.NIGHT) {
                        send("SYSTEM:능력은 밤에만 사용할 수 있습니다.");
                        continue;
                    }
                    switch (role) {
                        case POLICE:
                            currentRoom.handleInvestigate(this, msg);
                            break;
                        case DOCTOR:
                            currentRoom.handleSave(this, msg);
                            break;
                        case MAFIA:
                            currentRoom.handleKillCommand(this, msg);
                            break;
                        default:
                            send("SYSTEM:시민은 능력을 사용할 수 없습니다.");
                            break;
                    }
                }
                else if (msg.startsWith("CHAT:") ||
                        msg.startsWith("CHAT_MAFIA:") ||
                        msg.startsWith("CHAT_DEAD:")) {
                    currentRoom.handleChat(this, msg);
                }
                else {
                    send("SYSTEM:알 수 없는 명령어입니다.");
                    send("SYSTEM: 명령어"+msg);
                }
            }
        } catch (IOException e) {
            System.out.println("플레이어 " + playerNumber + " 연결 종료: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}

            if (currentRoom != null) {
                currentRoom.removePlayer(this);
                server.removeRoomIfEmpty(currentRoom);
            }
            server.removeSession(this);
        }
    }

    // ===== /room 명령 처리 =====

    private void handleRoomCommand(String cmd) {
        if (cmd.startsWith("list")) {
            // 서버의 방 목록 정보를 받아와서 ROOM_LIST:... 형식으로 전송
            java.util.List<String> infos = server.getRoomInfoList();
            String payload = String.join(",", infos);
            send("ROOM_LIST:" + payload);
            return;
        }

        if (cmd.startsWith("create")) {
            String roomName = cmd.substring("create".length()).trim();
            if (roomName.isEmpty()) {
                send("SYSTEM:방 이름을 입력하세요.");
                return;
            }

            GameRoom room = server.createRoom(roomName);
            if (room == null) {
                send("SYSTEM:이미 존재하는 방 이름입니다.");
                return;
            }

            if (currentRoom != null) {
                currentRoom.removePlayer(this);
                server.removeRoomIfEmpty(currentRoom);
            }

            currentRoom = room;
            room.addPlayer(this);
            send("SYSTEM:방 '" + roomName + "' 을(를) 생성하고 입장했습니다.");
            return;
        }

        if (cmd.startsWith("join")) {
            String roomName = cmd.substring("join".length()).trim();
            if (roomName.isEmpty()) {
                send("SYSTEM:방 이름을 입력하세요.");
                return;
            }

            GameRoom room = server.getRoom(roomName);
            if (room == null) {
                send("SYSTEM:존재하지 않는 방입니다.");
                return;
            }

            if (currentRoom != null) {
                currentRoom.removePlayer(this);
                server.removeRoomIfEmpty(currentRoom);
            }

            currentRoom = room;
            room.addPlayer(this);
            send("SYSTEM:방 '" + roomName + "' 에 입장했습니다.");
            return;
        }

        send("SYSTEM:잘못된 /room 명령입니다. (/room list | /room create 이름 | /room join 이름)");
    }
}
