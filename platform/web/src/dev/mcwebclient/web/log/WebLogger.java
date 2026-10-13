package dev.mcwebclient.web.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import dev.mcwebclient.web.WebJs;

/** Console logger; level threshold is global (see {@link #setThreshold}). */
final class WebLogger extends LegacyAbstractLogger {
    private static volatile int threshold = Level.INFO.toInt();

    WebLogger(String name) {
        this.name = name;
    }

    static void setThreshold(Level level) {
        threshold = level.toInt();
    }

    private static boolean on(Level level) {
        return level.toInt() >= threshold;
    }

    @Override
    public boolean isTraceEnabled() {
        return on(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return on(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return on(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return on(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return on(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] args, Throwable t) {
        String msg = MessageFormatter.basicArrayFormat(pattern, args);
        LocalTime now = LocalTime.now();
        StringBuilder sb = new StringBuilder(64 + (msg != null ? msg.length() : 4));
        sb.append('[').append(two(now.getHour())).append(':').append(two(now.getMinute())).append(':')
                .append(two(now.getSecond())).append("] [").append(Thread.currentThread().getName()).append('/')
                .append(level).append("] (").append(shortName()).append(") ").append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        WebJs.log(sb.toString());
    }

    private String shortName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }
}
