/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.source;

import org.gradle.api.Nullable;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.internal.io.LinePerThreadBufferingOutputStream;
import org.gradle.internal.io.TextStream;
import org.gradle.internal.logging.config.LoggingSourceSystem;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.StyledTextOutputEvent;
import org.gradle.internal.operations.BuildOperationIdentifierRegistry;
import org.gradle.internal.time.TimeProvider;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link LoggingSourceSystem} which routes content written to a {@code PrintStream} to a {@link OutputEventListener}.
 * Generates a {@link StyledTextOutputEvent} instance when a line of text is written to the {@code PrintStream}.
 * Generates a {@link LogLevelChangeEvent} when the log level for this {@code LoggingSystem} is changed.
 */
abstract class PrintStreamLoggingSystem implements LoggingSourceSystem {
    private final AtomicReference<StandardOutputListener> destination = new AtomicReference<StandardOutputListener>();
    private final PrintStream outstr = new LinePerThreadBufferingOutputStream(new TextStream() {
        public void text(String output) {
            destination.get().onOutput(output);
        }

        public void endOfStream(@Nullable Throwable failure) {
        }
    });
    private PrintStreamDestination original;
    private boolean enabled;
    private LogLevel logLevel;
    private final StandardOutputListener listener;
    private final OutputEventListener outputEventListener;

    protected PrintStreamLoggingSystem(OutputEventListener listener, String category, TimeProvider timeProvider) {
        outputEventListener = listener;
        this.listener = new OutputEventDestination(listener, category, timeProvider);
    }

    /**
     * Returns the current value of the PrintStream
     */
    protected abstract PrintStream get();

    /**
     * Sets the current value of the PrintStream
     */
    protected abstract void set(PrintStream printStream);

    public Snapshot snapshot() {
        return new SnapshotImpl(enabled, logLevel);
    }

    public void restore(Snapshot state) {
        SnapshotImpl snapshot = (SnapshotImpl) state;
        enabled = snapshot.enabled;
        logLevel = snapshot.logLevel;
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public Snapshot setLevel(LogLevel logLevel) {
        Snapshot snapshot = snapshot();
        if (logLevel != this.logLevel) {
            this.logLevel = logLevel;
            if (enabled) {
                outstr.flush();
                outputEventListener.onOutput(new LogLevelChangeEvent(logLevel));
            }
        }
        return snapshot;
    }

    @Override
    public Snapshot startCapture() {
        Snapshot snapshot = snapshot();
        if (!enabled) {
            install();
        }
        return snapshot;
    }

    private void uninstall() {
        if (original != null) {
            outstr.flush();
            destination.set(original);
            set(original.originalStream);
        }
    }

    private void install() {
        if (original == null) {
            PrintStream originalStream = get();
            original = new PrintStreamDestination(originalStream);
        }
        enabled = true;
        outstr.flush();
        outputEventListener.onOutput(new LogLevelChangeEvent(logLevel));
        destination.set(listener);
        if (get() != outstr) {
            set(outstr);
        }
    }

    private static class PrintStreamDestination implements StandardOutputListener {
        private final PrintStream originalStream;

        public PrintStreamDestination(PrintStream originalStream) {
            this.originalStream = originalStream;
        }

        public void onOutput(CharSequence output) {
            originalStream.print(output);
        }
    }

    private static class SnapshotImpl implements Snapshot {
        private final boolean enabled;
        private final LogLevel logLevel;

        public SnapshotImpl(boolean enabled, LogLevel logLevel) {
            this.enabled = enabled;
            this.logLevel = logLevel;
        }
    }

    private static class OutputEventDestination implements StandardOutputListener {
        private final OutputEventListener listener;
        private final String category;
        private final TimeProvider timeProvider;

        public OutputEventDestination(OutputEventListener listener, String category, TimeProvider timeProvider) {
            this.listener = listener;
            this.category = category;
            this.timeProvider = timeProvider;
        }

        public void onOutput(CharSequence output) {
            Object buildOperationId = BuildOperationIdentifierRegistry.getCurrentOperationIdentifier();
            StyledTextOutputEvent event = new StyledTextOutputEvent(timeProvider.getCurrentTime(), category, null, buildOperationId, output.toString());
            listener.onOutput(event);
        }
    }
}
