public class Player {
    private final String nickname;
    private Role role;
    private boolean isAlive;
    private boolean isReady;

    // 투표나 능력 사용 시 대상을 저장 (예: 투표 대상 닉네임)
    private String chosenTarget;

    public Player(String nickname) {
        this.nickname = nickname;
        this.isAlive = true; // 처음에는 모두 생존
        this.isReady = false; // 준비 상태는 False로 초기화
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setChosenTarget(String target) {
        this.chosenTarget = target;
    }

    // 사망 처리
    public void setDead() {
        this.isAlive = false;
        // 사망한 플레이어는 자신들만 보이는 채팅이 가능해야 합니다[cite: 7].
        // (이 로직은 ClientHandler와 Room에서 처리)
    }

    // Getter methods
    public String getNickname() { return nickname; }
    public Role getRole() { return role; }
    public boolean isAlive() { return isAlive; }
    public boolean isReady() { return isReady; }
    public String getChosenTarget() { return chosenTarget; }

    // Setter methods
    public void setReady(boolean ready) { this.isReady = ready; }

    // 능력 사용 후 대상 초기화
    public void resetTarget() { this.chosenTarget = null; }
}