declare module 'markdown-it' {
  interface MarkdownToken {
    attrSet(name: string, value: string): void
  }

  type RendererRule = (
    tokens: MarkdownToken[],
    index: number,
    options: unknown,
    env: unknown,
    self: MarkdownRenderer
  ) => string

  interface MarkdownRenderer {
    rules: Record<string, RendererRule | undefined>
    renderToken(tokens: MarkdownToken[], index: number, options: unknown): string
  }

  class MarkdownIt {
    renderer: MarkdownRenderer
    constructor(options?: unknown)
    render(source: string, env?: unknown): string
  }

  export default MarkdownIt
}
