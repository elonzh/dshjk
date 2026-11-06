package example;

import com.elonzh.dshjk.client.DeepSeekHarness;
import com.elonzh.dshjk.configuration.HarnessConfig;
import com.elonzh.dshjk.configuration.RuntimeCommand;
import java.nio.file.Path;

public final class JavaExample {
    public static void main(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: <dsh-cli.js> <isolated-home> [prompt]");
        var config = HarnessConfig.builder(Path.of(args[1]))
            .runtime(RuntimeCommand.node(Path.of(args[0])))
            .profile("sdk-minimal").build();
        try (var harness = new DeepSeekHarness(config)) {
            System.out.println(harness.start().getServerInfo());
            var session = harness.session();
            System.out.println(session.getWorkingDirectory());
            if (args.length > 2) {
                var result = session.run(args[2], notification -> System.out.println(notification.getMethod().getWireName()));
                System.out.println(result.getFinalResponse());
            }
        }
    }
}
