public class Timer extends Thread {
    private final GameEngine gameEngine;
    private int durationSeconds; // 타이머 지속 시간 (초)
    private volatile boolean isRunning = true;
    private volatile boolean skipRequested = false;

    public Timer(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    // 시간을 설정하고 타이머 시작
    public void startTimer(int durationSeconds) {
        this.durationSeconds = durationSeconds;
        this.isRunning = true;
        this.skipRequested = false;
        // 새로운 스레드를 시작하기 위해 super.start() 호출 대신 직접 run()을 호출하거나
        // Timer 객체를 매번 새로 생성해야 합니다. 여기서는 간결화를 위해 run()에 로직을 둡니다.
    }

    @Override
    public void run() {
        System.out.println("타이머 시작: " + durationSeconds + "초");

        try {
            for (int i = durationSeconds; i >= 0; i--) {
                if (!isRunning) {
                    break;
                }

                //[cite_start] 과반수 이상 찬성 시 시간을 넘길 수 있습니다[cite: 2].
                if (skipRequested) {
                    System.out.println("타이머: 과반수 찬성으로 시간이 스킵됩니다.");
                    break;
                }

                // 1초 대기
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (isRunning) {
            // 시간이 다 되었거나 스킵 요청이 들어온 경우 GameEngine에 다음 단계로 전환 요청
            gameEngine.endPhase();
        }
        this.isRunning = false;
    }

    // 외부에서 타이머 종료 요청 (스킵 기능)
    public void requestSkip() {
        //[cite_start] 투표를 진행하여 과반수 이상 [진행자의 절반]이 찬성한 경우 시간을 넘길 수 있다[cite: 2].
        // 실제 구현 시 과반수 확인 로직이 필요하며, 확인 후 이 함수를 호출합니다.
        this.skipRequested = true;
    }

    public void stopTimer() {
        this.isRunning = false;
    }
}