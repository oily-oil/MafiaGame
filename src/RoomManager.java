import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomManager {
    // key: 방 ID, value: Room 객체
    private final Map<String, Room> activeRooms;
    private final AtomicInteger nextRoomId = new AtomicInteger(1);

    public RoomManager() {
        this.activeRooms = new ConcurrentHashMap<>();
    }

    // 방 만들기
    public String createRoom(String roomTitle, int maxPlayers) {
        String newId = "ROOM_" + nextRoomId.getAndIncrement();
        Room newRoom = new Room(newId, roomTitle, maxPlayers);
        activeRooms.put(newId, newRoom);
        System.out.println("[RoomManager] 새로운 방 생성: " + newRoom.getRoomInfo());
        return newId; // 생성된 방 ID 반환
    }

    // 방 입장하기
    public boolean enterRoom(String roomId, String nickname, ClientHandler handler) {
        Room room = activeRooms.get(roomId);
        if (room != null) {
            return room.enterRoom(nickname, handler);
        }
        handler.sendMessage("SERVER: 유효하지 않은 방 ID입니다: " + roomId);
        return false; // 방 ID가 유효하지 않음
    }

    // 방 퇴장 처리 및 방 삭제 (RoomManager를 통해서만 방을 삭제해야 안전함)
    public void removeRoomIfEmpty(String roomId) {
        Room room = activeRooms.get(roomId);
        if (room != null && room.getCurrentPlayerCount() == 0) {
            activeRooms.remove(roomId);
            System.out.println("[RoomManager] 방 ID " + roomId + "가 삭제되었습니다. (인원 0)");
        }
    }

    // 클라이언트의 퇴장 요청을 처리하고 방 인원 확인 후 필요하면 방을 삭제
    public void leaveRoom(String roomId, String nickname) {
        Room room = activeRooms.get(roomId);
        if (room != null) {
            room.leaveRoom(nickname);
            removeRoomIfEmpty(roomId);
        }
    }

    // 현재 활성화된 모든 방 목록을 반환
    public String getRoomList() {
        if (activeRooms.isEmpty()) {
            return "--- 방 목록 ---\n현재 활성화된 방이 없습니다.\n---------------";
        }
        StringBuilder sb = new StringBuilder("--- 방 목록 ---\n");
        for (Room room : activeRooms.values()) {
            sb.append(room.getRoomInfo()).append("\n");
        }
        sb.append("---------------");
        return sb.toString();
    }

    // 기타 Getter
    public Room getRoom(String roomId) {
        return activeRooms.get(roomId);
    }
}