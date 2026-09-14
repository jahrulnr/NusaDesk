package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * Debug-build slf4j backend routing to android.util.Log. Present only in debug builds so
 * MINA SSHD's internal diagnostics are visible in logcat while investigating channel
 * behavior; release builds keep slf4j unbound (no-op) as before.
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder {

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    public static final String REQUESTED_API_VERSION = "1.7.36";

    private static final String LOGGER_FACTORY_CLASS = AndroidLoggerFactory.class.getName();

    private final ILoggerFactory factory = new AndroidLoggerFactory();

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    private StaticLoggerBinder() {
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return LOGGER_FACTORY_CLASS;
    }
}
