package org.slf4j.impl;

import android.util.Log;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * Single-argument slf4j logger backed by android.util.Log. MarkerIgnoringBase supplies the
 * parameterized variants via MessageFormatter. Debug level is enabled unconditionally so
 * MINA's channel/state diagnostics reach logcat in debug builds.
 */
final class AndroidLogger extends MarkerIgnoringBase {

    private static final long serialVersionUID = 1L;

    AndroidLogger(String name) {
        this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public void trace(String msg) {
        Log.v(name, msg);
    }

    @Override
    public void trace(String format, Object arg) {
        trace(MessageFormatter.format(format, arg).getMessage());
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        trace(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    @Override
    public void trace(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        if (tuple.getThrowable() == null) {
            trace(tuple.getMessage());
        } else {
            trace(tuple.getMessage(), tuple.getThrowable());
        }
    }

    @Override
    public void trace(String msg, Throwable t) {
        Log.v(name, msg, t);
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public void debug(String msg) {
        Log.d(name, msg);
    }

    @Override
    public void debug(String format, Object arg) {
        debug(MessageFormatter.format(format, arg).getMessage());
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        debug(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    @Override
    public void debug(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        if (tuple.getThrowable() == null) {
            debug(tuple.getMessage());
        } else {
            debug(tuple.getMessage(), tuple.getThrowable());
        }
    }

    @Override
    public void debug(String msg, Throwable t) {
        Log.d(name, msg, t);
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public void info(String msg) {
        Log.i(name, msg);
    }

    @Override
    public void info(String format, Object arg) {
        info(MessageFormatter.format(format, arg).getMessage());
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        info(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    @Override
    public void info(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        if (tuple.getThrowable() == null) {
            info(tuple.getMessage());
        } else {
            info(tuple.getMessage(), tuple.getThrowable());
        }
    }

    @Override
    public void info(String msg, Throwable t) {
        Log.i(name, msg, t);
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public void warn(String msg) {
        Log.w(name, msg);
    }

    @Override
    public void warn(String format, Object arg) {
        warn(MessageFormatter.format(format, arg).getMessage());
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        warn(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    @Override
    public void warn(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        if (tuple.getThrowable() == null) {
            warn(tuple.getMessage());
        } else {
            warn(tuple.getMessage(), tuple.getThrowable());
        }
    }

    @Override
    public void warn(String msg, Throwable t) {
        Log.w(name, msg, t);
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public void error(String msg) {
        Log.e(name, msg);
    }

    @Override
    public void error(String format, Object arg) {
        error(MessageFormatter.format(format, arg).getMessage());
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        error(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    @Override
    public void error(String format, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, arguments);
        if (tuple.getThrowable() == null) {
            error(tuple.getMessage());
        } else {
            error(tuple.getMessage(), tuple.getThrowable());
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        Log.e(name, msg, t);
    }
}
