package example

import com.elonzh.dshjk.client.DeepSeekHarness
import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.RuntimeCommand
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main(args: Array<String>) = runBlocking {
    require(args.size >= 2) { "Usage: <dsh-cli.js> <isolated-home> [prompt]" }
    val harness = DeepSeekHarness(HarnessConfig(dshHome = Path.of(args[1]), runtime = RuntimeCommand.node(Path.of(args[0])), profile = "sdk-minimal"))
    try {
        println(harness.start().serverInfo)
        val session = harness.session()
        println(session.getWorkingDirectory())
        if (args.size > 2) println(session.run(args[2]).finalResponse)
    } finally { harness.closeAsync() }
}
