import { spawn } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'

export const name = 'dshjk-cli-fixture'
export const inject = ['cmdlineArgs', 'appReady', 'appExit']

export function apply(ctx, config) {
  const [command, value] = ctx.cmdlineArgs.get()
  ctx.effect(() => ctx.appReady.onReady(() => {
    switch (command) {
      case 'echo': {
        const output = { value, cwd: process.cwd(), home: process.env.DSH_HOME,
          token: process.env.DSHJK_TEST_TOKEN, layer: config.layer, input: readFileSync(0, 'utf8') }
        process.stdout.write(JSON.stringify(output), () => ctx.appExit(0))
        break
      }
      case 'flood':
        process.stdout.write('x'.repeat(1024 * 1024 + 1), () => ctx.appExit(0))
        break
      case 'hang': {
        const child = spawn('node', ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' })
        child.once('spawn', () => writeFileSync(value, String(child.pid)))
        ctx.effect(() => {
          const timer = setInterval(() => {}, 1000)
          return () => clearInterval(timer)
        }, 'fixture.keep-alive')
        break
      }
      default:
        process.stderr.write('Unknown fixture command\n', () => ctx.appExit(2))
    }
  }), 'fixture.cli')
}
