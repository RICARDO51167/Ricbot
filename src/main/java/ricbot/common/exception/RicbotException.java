package ricbot.common.exception;

public class RicbotException extends RuntimeException {
    public RicbotException(String message) {
        super(message);
    }

    public RicbotException(String message, Throwable cause) {
        super(message, cause);
    }
}