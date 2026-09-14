package org.slf4j.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Maps slf4j logger names to compact logcat tags (logcat tags are limited to 23 chars). */
public final class AndroidLoggerFactory implements ILoggerFactory {

    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return loggers.computeIfAbsent(tagFor(name), AndroidLogger::new);
    }

    private static String tagFor(String name) {
        String simple = name;
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1) {
            simple = name.substring(lastDot + 1);
        }
        String tag = "slf4j-" + simple;
        return tag.length() > 23 ? tag.substring(0, 23) : tag;
    }
}
