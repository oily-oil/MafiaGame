public enum Role {
    // 4명부터: 마피아, 경찰, 시민
    MAFIA("마피아", Team.MAFIA),
    POLICE("경찰", Team.CITIZEN),
    CITIZEN("시민", Team.CITIZEN),

    // 6명부터: 의사 추가
    DOCTOR("의사", Team.CITIZEN);

    private final String koreanName;
    private final Team team;

    Role(String koreanName, Team team) {
        this.koreanName = koreanName;
        this.team = team;
    }

    public String getKoreanName() {
        return koreanName;
    }

    public Team getTeam() {
        return team;
    }

    public enum Team {
        MAFIA, CITIZEN
    }
}