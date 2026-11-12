"""Add remote desktop session records.

Remote desktop sessions are the audit and state-machine source of truth. They
survive connector and user deletion on purpose: deleting a device or a user
must not erase the record of who viewed or controlled which screen. The
``connector_id`` and ``user_id`` columns therefore carry **no foreign keys** —
an ``ON DELETE CASCADE`` would silently erase the audit trail (same reasoning
as ``usage_events``).

Timestamps use the repository-wide TEXT/RFC3339 convention and structured
payloads (quality, stats summary, error, participants) are JSON strings, so
this migration uses no ``JSONB``/``TIMESTAMPTZ``.

Revision ID: v2_56
Revises: v2_55
"""

import sqlalchemy as sa
from alembic import op

revision = "v2_56"
down_revision = "v2_55"
branch_labels = None
depends_on = None


def _table_names() -> set[str]:
    return set(sa.inspect(op.get_bind()).get_table_names())


def upgrade() -> None:
    if "remote_desktop_sessions" in _table_names():
        return
    op.create_table(
        "remote_desktop_sessions",
        sa.Column("id", sa.Text(), nullable=False),
        sa.Column("connector_id", sa.Text(), nullable=False),
        sa.Column("connector_name", sa.Text()),
        sa.Column("user_id", sa.Text(), nullable=False),
        sa.Column("created_by_participant_id", sa.Text()),
        sa.Column("mode", sa.Text(), nullable=False),
        sa.Column("display_id", sa.Text()),
        sa.Column("state", sa.Text(), nullable=False),
        sa.Column("end_reason", sa.Text()),
        sa.Column("controller_identity", sa.Text()),
        sa.Column("controller_lease_expires_at", sa.Text()),
        sa.Column("linked_session_id", sa.Text()),
        sa.Column("media_provider", sa.Text(), nullable=False),
        sa.Column("media_room", sa.Text()),
        sa.Column("quality_json", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("stats_summary_json", sa.Text()),
        sa.Column("error_json", sa.Text()),
        sa.Column("participants_json", sa.Text(), nullable=False, server_default="[]"),
        sa.Column("consent_deadline_at", sa.Text()),
        sa.Column("interrupted_at", sa.Text()),
        sa.Column("last_input_at", sa.Text()),
        sa.Column("last_viewer_left_at", sa.Text()),
        sa.Column("started_at", sa.Text()),
        sa.Column("ended_at", sa.Text()),
        sa.Column("worker_id", sa.Text()),
        sa.Column("created_at", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.Text(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    # One live session per connector (decisions D1).
    op.create_index(
        "uq_rd_sessions_connector_active",
        "remote_desktop_sessions",
        ["connector_id"],
        unique=True,
        postgresql_where=sa.text("ended_at IS NULL"),
    )
    op.create_index(
        "idx_rd_sessions_connector_created",
        "remote_desktop_sessions",
        ["connector_id", sa.text("created_at DESC")],
    )
    op.create_index(
        "idx_rd_sessions_user_created",
        "remote_desktop_sessions",
        ["user_id", sa.text("created_at DESC")],
    )
    op.create_index(
        "idx_rd_sessions_state_active",
        "remote_desktop_sessions",
        ["state"],
        postgresql_where=sa.text("ended_at IS NULL"),
    )
    op.create_index(
        "idx_rd_sessions_ended",
        "remote_desktop_sessions",
        ["ended_at"],
    )


def downgrade() -> None:
    if "remote_desktop_sessions" in _table_names():
        op.drop_table("remote_desktop_sessions")
