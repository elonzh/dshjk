package example.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(proxyBeanMethods = false)
class HarnessApplication

fun main(args: Array<String>) {
    runApplication<HarnessApplication>(*args)
}
