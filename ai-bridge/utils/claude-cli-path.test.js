import test from 'node:test';
import assert from 'node:assert/strict';
import { getClaudeCliPathOverride } from './claude-cli-path.js';

/**
 * Temporarily applies CLAUDE_CODE_PATH for the duration of a single assertion.
 */
function withEnv(value, fn) {
  const had = Object.prototype.hasOwnProperty.call(process.env, 'CLAUDE_CODE_PATH');
  const prev = process.env.CLAUDE_CODE_PATH;
  if (value === undefined) {
    delete process.env.CLAUDE_CODE_PATH;
  } else {
    process.env.CLAUDE_CODE_PATH = value;
  }
  try {
    return fn();
  } finally {
    if (had) {
      process.env.CLAUDE_CODE_PATH = prev;
    } else {
      delete process.env.CLAUDE_CODE_PATH;
    }
  }
}

test('returns null when CLAUDE_CODE_PATH is unset', () => {
  withEnv(undefined, () => {
    assert.equal(getClaudeCliPathOverride(), null);
  });
});

test('returns null when CLAUDE_CODE_PATH is blank/whitespace only', () => {
  withEnv('   \t  ', () => {
    assert.equal(getClaudeCliPathOverride(), null);
  });
});

test('trims surrounding whitespace from the configured path', () => {
  withEnv('  /usr/local/bin/claude  ', () => {
    assert.equal(getClaudeCliPathOverride(), '/usr/local/bin/claude');
  });
});

test('returns the path unchanged when already clean', () => {
  withEnv('/opt/claude/bin/claude', () => {
    assert.equal(getClaudeCliPathOverride(), '/opt/claude/bin/claude');
  });
});
