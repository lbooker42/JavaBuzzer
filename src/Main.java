import com.biblequizsoftware.BuzzerState;

public class Main {
    public static void main(String[] args) {
        BuzzerState bqsBuzzer = new BuzzerState((cmd, timeStamp) -> {
            // example of actions to handle
            switch (cmd) {
                case CLEAR -> {
                    System.out.println(timeStamp + ": Buzzer cleared");
                }
                case R1 -> {
                    System.out.println(timeStamp + ": R1 pressed");
                }
                case R2 -> {
                    System.out.println(timeStamp + ": R2 pressed");
                }
                case R3 -> {
                    System.out.println(timeStamp + ": R3 pressed");
                }
                case Y1 -> {
                    System.out.println(timeStamp + ": Y1 pressed");
                }
                case Y2 -> {
                    System.out.println(timeStamp + ": Y2 pressed");
                }
                case Y3 -> {
                    System.out.println(timeStamp + ": Y3 pressed");
                }
                case QM -> {
                    System.out.println(timeStamp + ": QM pressed");
                }
                default -> {
                    System.out.println(timeStamp + ": " + cmd);
                }
            }
        });
        // only enabling exit on close for the sake of this demo application
        bqsBuzzer.showDialog(true);
    }
}