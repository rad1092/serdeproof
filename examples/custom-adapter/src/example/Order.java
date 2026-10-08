package example;

import java.math.BigDecimal;

/** Stand-in for the DTO already present in a service; keep your production DTO unchanged. */
public record Order(String id, BigDecimal total, Status status) {
    public enum Status { CREATED, PAID }
}
