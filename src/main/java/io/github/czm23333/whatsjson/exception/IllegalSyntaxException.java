package io.github.czm23333.whatsjson.exception;

public class IllegalSyntaxException extends RuntimeException {
    public IllegalSyntaxException() {
        super();
    }

    public IllegalSyntaxException(String msg) {
        super(msg);
    }
}