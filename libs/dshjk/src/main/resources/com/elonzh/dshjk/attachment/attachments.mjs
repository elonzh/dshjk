import { createWriteStream } from 'node:fs'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { promisify } from 'node:util'

export const name = 'dshjk-attachments'
export const inject = ['attachments', 'cmdlineArgs', 'appReady', 'appExit']

const writeOutput = promisify(process.stdout.write.bind(process.stdout))
async function run(ctx, args) {
  let exitCode = 0
  try {
    let response
    try {
      response = { result: await main(ctx.attachments, args) }
    } catch (error) {
      exitCode = 1
      response = { error: {
        code: typeof error?.code === 'string' ? error.code : 'ATTACHMENT_OPERATION_FAILED',
        message: error instanceof Error ? error.message : String(error),
      } }
    }
    await writeOutput(JSON.stringify(response))
  } catch (error) {
    exitCode = 1
    process.stderr.write(`Cannot write attachment command result: ${error}\n`)
  } finally {
    ctx.appExit(exitCode)
  }
}

export function apply(ctx) {
  const args = ctx.cmdlineArgs.get()
  ctx.effect(() => ctx.appReady.onReady(() => {
    void run(ctx, args)
  }), 'dshjk.attachments.cli')
}

async function main(store, args) {
  const [command, value, option, destination] = args
  if (command === 'save-image' && value && args.length <= 3) {
    const data = await readImageInput(store.imageLimits.maxImageBytes)
    return await store.saveImage({ data, mediaType: value, name: option })
  }
  if (command === 'save-file' && value && args.length === 2) {
    return await store.saveFileStream({ data: process.stdin, name: value })
  }
  if ((command === 'read-image' || command === 'read-file') && value && option === '--output' && destination && args.length === 4) {
    const ref = JSON.parse(value)
    const bytes = command === 'read-image' ? [(await store.readImage(ref)).data] : store.readFileStream(ref)
    await pipeline(Readable.from(bytes), createWriteStream(destination, { flags: 'wx' }))
    return null
  }
  throw new Error('Invalid attachment command or arguments.')
}

async function readImageInput(limit) {
  const chunks = []
  let size = 0
  for await (const chunk of process.stdin) {
    size += chunk.length
    if (size > limit) {
      const error = new Error('Image exceeds the configured byte limit.')
      error.code = 'IMAGE_TOO_LARGE'
      throw error
    }
    chunks.push(chunk)
  }
  return Buffer.concat(chunks, size)
}
