import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Room {
    private final String roomId;
    private final String roomTitle;
    private final int maxPlayers;

    // key: 클라이언트 닉네임, value: 해당 클라이언트를 처리하는 ClientHandler
    // 게임 시작 후에는 GameEngine이 생성됩니다.
    private final Map<String, ClientHandler> clients;
    private GameEngine gameEngine; // 게임이 시작되면 여기에 로직 엔진이 할당됨

    public Room(String roomId, String roomTitle, int maxPlayers) {
        this.roomId = roomId;
        this.roomTitle = roomTitle;
        this.maxPlayers = maxPlayers;
        // 여러 스레드에서 접근 가능하도록 ConcurrentHashMap 사용
        this.clients = new ConcurrentHashMap<>();
    }

    // 클라이언트 추가 (방 입장)
    public boolean enterRoom(String nickname, ClientHandler handler) {
        if (clients.size() < maxPlayers) {
            clients.put(nickname, handler);
            System.out.println(nickname + "님이 방 [" + roomTitle + "]에 입장했습니다. 현재 인원: " + getCurrentPlayerCount());
            // 클라이언트에게 방 입장 성공 메시지 전송
            handler.sendMessage("SERVER: 방 '" + roomTitle + "'에 입장했습니다. (현재 인원: " + getCurrentPlayerCount() + "/" + maxPlayers + ")");
            return true;
        }
        handler.sendMessage("SERVER: 방 입장에 실패했습니다. 정원 초과입니다.");
        return false; // 정원 초과
    }

    // 클라이언트 제거 (방 퇴장/연결 종료)
    public void leaveRoom(String nickname) {
        clients.remove(nickname);
        System.out.println(nickname + "님이 방 [" + roomTitle + "]에서 퇴장했습니다. 남은 인원: " + getCurrentPlayerCount());
        
        //[cite_start] **예외사항 처리:** 클라이언트 종료는 게임에서 사망으로 처리해야 합니다. [cite: 8]
        // 게임 진행 중이었다면 GameEngine에 사망 처리 로직을 호출해야 합니다.
        if (this.gameEngine != null) {
            // gameEngine.handlePlayerDeath(nickname); // (GameEngine에 구현 예정)
        }
    }

    // 현재 방에 있는 클라이언트 수를 반환
    public int getCurrentPlayerCount() {
        return clients.size();
    }

    // 현재 방 정보 문자열 반환 (목록 표시용)
    public String getRoomInfo() {
        return "ID: " + roomId + ", 제목: " + roomTitle +
                " (" + getCurrentPlayerCount() + "/" + maxPlayers + ")";
    }

    // 방 내부 모든 클라이언트에게 메시지 전송 (예시: 공지/채팅)
    public void broadcast(String message) {
        for (ClientHandler handler : clients.values()) {
            handler.sendMessage(message);
        }
    }

    // Getter methods
    public String getRoomId() { return roomId; }
    public String getRoomTitle() { return roomTitle; }
    public Map<String, ClientHandler> getClients() { return clients; }
    public int getMaxPlayers() { return maxPlayers; }

    // 게임 시작 로직 (ClientHandler에서 호출됨)
    public boolean startGame(String initiatorNickname) {
        if (!clients.containsKey(initiatorNickname)) {
            return false; // 시작 권한이 없는 사용자
        }
        
        //[cite_start] 인원수가 4명 이하일 경우 게임을 시작할 수 없음. [cite: 8]
        if (getCurrentPlayerCount() < 4) {
            broadcast("SERVER: 게임을 시작할 수 없습니다. 최소 4명 이상이 필요합니다. [cite: 8]");
            return false;
        }
        
        //[cite_start] 모든 인원이 준비 상태인지 확인하는 로직 추가 필요 [cite: 8]
        // 현재는 준비 상태를 생략하고 인원수만 체크합니다.

        //[cite_start] 게임 시작 권한 확인 및 권한 위임 로직은 ClientHandler/RoomManager에서 관리 필요 [cite: 8]

        // 💡 GameEngine 인스턴스 생성 및 게임 시작
        this.gameEngine = new GameEngine(this); // Room 자신을 GameEngine에 넘겨 상태 관리를 위임
        // this.gameEngine.distributeRoles(); // (GameEngine에 구현 예정)
        // this.gameEngine.startDayPhase(); // (GameEngine에 구현 예정)
        broadcast("SERVER: 게임이 시작됩니다! 직업을 확인하세요.");
        return true;
    }
}