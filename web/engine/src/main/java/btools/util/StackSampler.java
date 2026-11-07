package btools.util;

import java.io.File;

/**
 * Web build only: replaces BRouter's StackSampler (a debug thread that dumps all thread stacks to a file; it
 * starts only when a "stacksampler" debug file exists, never on the phone or the web). Same constructor and
 * calls, does nothing – TeaVM has no thread dumps.
 */
public class StackSampler {
    public StackSampler(File file, int interval) { }
    public void start() { }
    public void close() { }
}
