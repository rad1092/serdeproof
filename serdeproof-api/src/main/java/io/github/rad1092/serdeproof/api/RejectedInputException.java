package io.github.rad1092.serdeproof.api;

/** Expected serializer rejection. Messages and causes are never returned in a public report. */
public class RejectedInputException extends Exception {
    public RejectedInputException() { super(); }
    public RejectedInputException(String message) { super(message); }
    public RejectedInputException(String message, Throwable cause) { super(message, cause); }
    public RejectedInputException(Throwable cause) { super(cause); }
}
