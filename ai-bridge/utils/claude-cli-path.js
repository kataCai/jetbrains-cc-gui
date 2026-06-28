/**
 * Resolve a user-configured Claude Code CLI executable override from the environment.
 *
 * The Java bridge injects CLAUDE_CODE_PATH into child process environments when the
 * user has configured a custom Claude CLI path in settings. Claude SDK call sites can
 * then forward it to `pathToClaudeCodeExecutable`.
 */
export function getClaudeCliPathOverride() {
  const raw = process.env.CLAUDE_CODE_PATH;
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : null;
}

