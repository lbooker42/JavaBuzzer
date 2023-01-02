import com.biblequizsoftware.BuzzerState;

public class Main {
    public static void main(String[] args) {
        BuzzerState bqsBuzzer = new BuzzerState((cmd) -> {
            // example of actions to handle
            switch (cmd) {
                case R1 -> {
                    System.out.println("R1 was pressed");
                }
                case CLEAR -> {
                    System.out.println("Buzzer cleared");
                }
                // NOTE: the buzzer will automatically clear after signalling CORRECT or ERROR.
                // There will not be a separate CLEAR event in this case
                case CORRECT -> {
                    System.out.println("Marked correct");
                }
                case ERROR -> {
                    System.out.println("Marked error");
                }
                default -> {
                    System.out.println(cmd);
                }
            }
        });
        // only enabling exit on close for the sake of this demo application
        bqsBuzzer.showDialog(true);
    }
}