package example;

import com.elonzh.dshjk.client.DeepSeekHarness;
import com.elonzh.dshjk.error.TransportClosedException;
import com.elonzh.dshjk.protocol.ContentBlock;
import com.elonzh.dshjk.protocol.HarnessNotification;
import com.elonzh.dshjk.protocol.SessionEventData;
import com.elonzh.dshjk.protocol.TurnEndReason;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streams native SDK events; assistant text arrives when each model step is recorded. */
public final class JavaExample {
    public static void main(String[] args) {
        try (var harness = new DeepSeekHarness()) {
            System.err.println("[runtime] " + harness.start().getServerInfo());
            var session = harness.session();
            System.err.println("[session] " + session.getId());
            System.err.println("[cwd] " + session.getWorkingDirectory());
            if (args.length == 0) return;

            // Register before run(), so fast notifications are buffered until the subscriber requests them.
            try (var notifications = harness.getClient().subscribe(session.getId());
                 var console = new ConsoleSubscriber(session.getId())) {
                notifications.events().subscribe(console);
                var result = session.run(args[0]);

                // Closing the owner ends the stream after its buffered events have drained.
                console.expectRuntimeClose();
                harness.close();
                console.awaitCompletion();
                var finish = result.getFinishReason();
                System.err.println("[run complete] " + (finish == null ? "no terminal event" : finish.getWireName()));
                // Text was already printed by onNext(); do not print finalResponse again.
            }
        }
    }

    private static final class ConsoleSubscriber implements Flow.Subscriber<HarnessNotification>, AutoCloseable {
        private final String rootSessionId;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private volatile Flow.Subscription upstream;

        ConsoleSubscriber(String rootSessionId) { this.rootSessionId = rootSessionId; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            upstream = subscription;
            subscription.request(1);
        }

        @Override public void onNext(HarnessNotification notification) {
            display(notification, rootSessionId);
            upstream.request(1); // Ask for the next event only after handling this one.
        }

        @Override public void onError(Throwable error) {
            if (closing.get() && error instanceof TransportClosedException) completed.complete(null);
            else completed.completeExceptionally(error);
        }

        @Override public void onComplete() { completed.complete(null); }
        void expectRuntimeClose() { closing.set(true); }
        void awaitCompletion() { completed.join(); }
        @Override public void close() { if (upstream != null) upstream.cancel(); }
    }

    private static void display(HarnessNotification notification, String rootSessionId) {
        if (notification instanceof HarnessNotification.Event event) {
            var data = event.getEvent().getData();
            var prefix = "[" + event.getSessionId() + "] ";
            if (data instanceof SessionEventData.AssistantMessage message) {
                var text = new StringBuilder();
                for (var block : message.getMessage().getContent()) {
                    if (block instanceof ContentBlock.Text part) text.append(part.getText());
                }
                if (event.getSessionId().equals(rootSessionId)) printText(text.toString());
                else System.err.println(prefix + "assistant: " + text);
            } else if (data instanceof SessionEventData.TurnStarted turn) {
                System.err.println(prefix + "turn " + turn.getTurn() + " started");
            } else if (data instanceof SessionEventData.ToolCalled call) {
                System.err.println(prefix + "tool " + call.getName() + " started: " + call.getCallId());
            } else if (data instanceof SessionEventData.ToolResult result) {
                var status = Boolean.TRUE.equals(result.getMessage().isError()) ? "error" : "ok";
                System.err.println(prefix + "tool result " + result.getMessage().getToolCallId() + ": " + status);
            } else if (data instanceof SessionEventData.CommandRun command) {
                System.err.println(prefix + "command /" + command.getName() + " started");
            } else if (data instanceof SessionEventData.CommandDone command) {
                System.err.println(prefix + "command " + command.getCommandId() + ": " + command.getKind().getWireName());
                if (event.getSessionId().equals(rootSessionId) && command.getText() != null) printText(command.getText());
            } else if (data instanceof SessionEventData.TurnEnded turn) {
                System.err.println(prefix + "turn " + turn.getTurn() + ": " + turn.getReason().getKind().getWireName());
                if (turn.getReason() instanceof TurnEndReason.Error failed) {
                    System.err.println(prefix + failed.getError().getCode() + ": " + failed.getError().getMessage());
                }
            }
        } else if (notification instanceof HarnessNotification.Status status) {
            System.err.println("[" + status.getSessionId() + "] status: " + status.getStatus().getWireName());
        } else if (notification instanceof HarnessNotification.SubagentStarted child) {
            System.err.println("[subagent started] " + child.getChildSessionId());
        } else if (notification instanceof HarnessNotification.SubagentFinished child) {
            System.err.println("[subagent finished] " + child.getChildSessionId() + ": " + child.getStopReason().getWireName());
        }
    }

    private static void printText(String text) {
        if (text.isEmpty()) return;
        System.out.print(text);
        if (!text.endsWith("\n")) System.out.println();
        System.out.flush();
    }
}
