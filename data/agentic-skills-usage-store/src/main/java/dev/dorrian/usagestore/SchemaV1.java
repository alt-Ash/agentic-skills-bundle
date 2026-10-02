package dev.dorrian.usagestore;

import java.util.List;

/** Schema version 1. Add a SchemaV2 and a migration step rather than editing this once released. */
final class SchemaV1 {

    private SchemaV1() {
    }

    static final List<String> STATEMENTS = List.of(
        """
        CREATE TABLE IF NOT EXISTS events (
          event_id TEXT PRIMARY KEY,
          ts TEXT NOT NULL,
          event TEXT NOT NULL,
          session_id TEXT,
          provider TEXT,
          user_name TEXT,
          project TEXT,
          client TEXT,
          cwd TEXT,
          source TEXT,
          reason TEXT,
          model TEXT,
          input_tokens INTEGER,
          cached_tokens INTEGER,
          tool_name TEXT,
          tool_use_id TEXT,
          error TEXT,
          duration_ms INTEGER,
          stop_hook_active INTEGER,
          last_message_char_length INTEGER,
          estimated_output_tokens INTEGER,
          background_task_count INTEGER,
          prompt_char_length INTEGER,
          estimated_input_tokens INTEGER,
          permission_mode TEXT,
          prompt_id TEXT,
          git_start_commit TEXT,
          git_lines_added INTEGER,
          git_lines_deleted INTEGER,
          command TEXT,
          slash_command TEXT,
          guard_rule TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)",
        "CREATE INDEX IF NOT EXISTS idx_events_project ON events(project)",
        "CREATE INDEX IF NOT EXISTS idx_events_kind_tool ON events(event, tool_name)",
        """
        CREATE TABLE IF NOT EXISTS git_commits (
          event_id TEXT NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
          hash TEXT,
          message TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_git_commits_event ON git_commits(event_id)",
        """
        CREATE TABLE IF NOT EXISTS git_files (
          event_id TEXT NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
          path TEXT NOT NULL,
          change TEXT NOT NULL CHECK (change IN ('A','M','D'))
        )""",
        "CREATE INDEX IF NOT EXISTS idx_git_files_event ON git_files(event_id)",
        """
        CREATE TABLE IF NOT EXISTS sessions (
          session_id TEXT PRIMARY KEY,
          user_name TEXT,
          project TEXT,
          client TEXT,
          cwd TEXT,
          git_start_commit TEXT,
          started_at TEXT,
          ended_at TEXT
        )""",
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)"
    );

    /** Inserts into the full current column set (v1 + v2 + v3): the schema is always migrated before use. */
    static final String INSERT_EVENT = """
        INSERT OR IGNORE INTO events (
          event_id, ts, event, session_id, provider, user_name, project, client, cwd, source, reason, model,
          input_tokens, cached_tokens, tool_name, tool_use_id, error, duration_ms, stop_hook_active,
          last_message_char_length, estimated_output_tokens, background_task_count, prompt_char_length,
          estimated_input_tokens, permission_mode, prompt_id, git_start_commit, git_lines_added,
          git_lines_deleted, command, slash_command, guard_rule,
          output_tokens, cache_read_tokens, cache_creation_tokens, agent_name, skill_name, is_interrupt
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    static final String UPSERT_SESSION = """
        INSERT INTO sessions (session_id, user_name, project, client, cwd, git_start_commit, started_at, ended_at)
        VALUES (?,?,?,?,?,?,?,?)
        ON CONFLICT(session_id) DO UPDATE SET
          user_name = COALESCE(excluded.user_name, user_name),
          project = COALESCE(excluded.project, project),
          client = COALESCE(excluded.client, client),
          cwd = COALESCE(excluded.cwd, cwd),
          git_start_commit = COALESCE(git_start_commit, excluded.git_start_commit),
          started_at = MIN(COALESCE(started_at, excluded.started_at), COALESCE(excluded.started_at, started_at)),
          ended_at = COALESCE(excluded.ended_at, ended_at)""";
}
