package org.familyhealthcare.service.careplan;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

/** One request's monotonic deadline and cumulative source/output byte budgets. */
public final class CareExecutionReportBudget {
    private final long startedAtNanos, timeoutNanos;
    private final java.util.function.LongSupplier nanoTime;
    private long sourceTextBytes, outputBytes;

    private CareExecutionReportBudget(Duration timeout) {
        this(timeout, System::nanoTime);
    }
    private CareExecutionReportBudget(Duration timeout, java.util.function.LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Report timeout must not be negative.");
        long nanos;
        try { nanos = timeout.toNanos(); } catch (ArithmeticException e) { nanos = Long.MAX_VALUE; }
        this.timeoutNanos = nanos;
        this.startedAtNanos = nanoTime.getAsLong();
    }
    public static CareExecutionReportBudget start(Duration timeout) { return new CareExecutionReportBudget(timeout); }

    public void checkTime() {
        // Subtraction also works when nanoTime wraps; adding a deadline could overflow.
        if (nanoTime.getAsLong() - startedAtNanos >= timeoutNanos) throw CareExecutionReportException.timeout();
    }

    /** JDBC uses whole seconds; round up and check the exact monotonic deadline after each read. */
    public int remainingQuerySeconds() {
        long elapsed = nanoTime.getAsLong() - startedAtNanos;
        if (elapsed >= timeoutNanos) throw CareExecutionReportException.timeout();
        long remaining = timeoutNanos - elapsed;
        return (int) Math.min(Integer.MAX_VALUE, 1 + (remaining - 1) / 1_000_000_000L);
    }

    public void requireRows(String kind, long count, long limit) {
        checkTime();
        if (!("CURRENT_ACTIONS".equals(kind) || "PERIOD_EVENTS".equals(kind) || "QUESTIONS".equals(kind)) || count < 0 || limit < 0) {
            throw new IllegalArgumentException("Invalid report row budget.");
        }
        if (count > limit) throw CareExecutionReportException.limitExceeded(kind, limit);
    }

    public void addSourceText(String value) {
        checkTime();
        if (value == null) return;
        long bytes = 0;
        long remaining = CareExecutionReportContracts.MAX_SOURCE_TEXT_BYTES - sourceTextBytes;
        // Count Java's UTF-8 encoding without allocating an unbounded intermediate byte array.
        int pollAfter = 4096;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else if (Character.isSurrogate(c)) bytes++; // The UTF-8 encoder's replacement is '?'.
            else bytes += 3;
            if (bytes > remaining) throw CareExecutionReportException.limitExceeded("SOURCE_TEXT_BYTES", CareExecutionReportContracts.MAX_SOURCE_TEXT_BYTES);
            if (i >= pollAfter) { checkTime(); pollAfter = i + 4096; }
        }
        checkTime();
        sourceTextBytes += bytes;
    }

    public OutputStream output(OutputStream delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                requireOutputBytes(1); delegate.write(value); outputBytes++;
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.requireNonNull(bytes, "bytes");
                if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
                requireOutputBytes(length); delegate.write(bytes, offset, length); outputBytes += length;
            }
            @Override public void flush() throws IOException { checkTime(); delegate.flush(); }
            @Override public void close() throws IOException { delegate.close(); }
        };
    }
    private void requireOutputBytes(long bytes) {
        checkTime();
        if (bytes > CareExecutionReportContracts.MAX_OUTPUT_BYTES - outputBytes) {
            throw CareExecutionReportException.limitExceeded("OUTPUT_BYTES", CareExecutionReportContracts.MAX_OUTPUT_BYTES);
        }
    }
}
