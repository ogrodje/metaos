import type { Translate } from '@/i18n'

// MetaOS's MCP tools, for the Usage page, by what they work on. A tool that's added, renamed, removed or does
// something else changes here too (CLAUDE.md), and so do its description and its group's in the messages, in both
// languages (tools.descriptions.<name>, tools.groups.<id>, usage.spec.ts checks they're there); UsageToolsTest checks the
// names, but not the descriptions. The names are the tools' own, in every language.

/** The tools' version, as MetaOS gives it to assistants, which `just mcp-tools` writes (McpToolsTest) */
export const TOOLS_VERSION = '1.1.0'

export interface Tool {
  name: string
  description: string
}

export interface ToolGroup {
  id: string
  title: string
  about: string
  tools: Tool[]
}

/** The groups and their tools, by name: what they're called and what they do are in the messages */
export const TOOL_GROUPS: { id: string, tools: { name: string }[] }[] = [
  {
    id: 'search',
    tools: [
      { name: 'search' },
    ],
  },
  {
    id: 'episodes',
    tools: [
      { name: 'list_episodes' },
      { name: 'get_episode' },
      { name: 'create_episode' },
      { name: 'set_episode_recommended_by' },
      { name: 'update_episode' },
      { name: 'set_episode_status' },
      { name: 'set_episode_link' },
      { name: 'delete_episode' },
      { name: 'restore_episode' },
    ],
  },
  {
    id: 'distribution',
    tools: [
      { name: 'list_episode_distributions' },
      { name: 'list_distributions' },
      { name: 'add_episode_distribution' },
      { name: 'update_episode_distribution' },
      { name: 'remove_episode_distribution' },
      { name: 'refresh_distribution_preview' },
    ],
  },
  {
    id: 'people',
    tools: [
      { name: 'list_people' },
      { name: 'create_person' },
      { name: 'set_person_email' },
      { name: 'set_person_bio' },
      { name: 'set_person_link' },
      { name: 'delete_person' },
      { name: 'restore_person' },
      { name: 'list_episode_people' },
      { name: 'assign_person_to_episode' },
      { name: 'remove_person_from_episode' },
      { name: 'confirm_guest' },
      { name: 'list_person_episodes' },
      { name: 'whoami' },
      { name: 'my_episodes' },
      { name: 'list_accounts' },
      { name: 'link_account' },
      { name: 'unlink_account' },
    ],
  },
  {
    id: 'sessions',
    tools: [
      { name: 'list_sessions' },
      { name: 'create_session' },
      { name: 'update_session' },
      { name: 'delete_session' },
      { name: 'restore_session' },
      { name: 'list_session_episodes' },
      { name: 'set_episode_session' },
    ],
  },
  {
    id: 'discussion',
    tools: [
      { name: 'list_episode_comments' },
      { name: 'list_session_comments' },
      { name: 'comment_on_episode' },
      { name: 'comment_on_session' },
      { name: 'edit_comment' },
      { name: 'delete_comment' },
    ],
  },
  {
    id: 'shows',
    tools: [
      { name: 'list_shows' },
      { name: 'get_show' },
      { name: 'create_show' },
      { name: 'update_show' },
      { name: 'delete_show' },
      { name: 'restore_show' },
      { name: 'list_show_episodes' },
      { name: 'set_episode_show' },
    ],
  },
  {
    id: 'drive',
    tools: [
      { name: 'list_drive_folder' },
      { name: 'search_documents' },
      { name: 'read_document' },
      { name: 'list_episode_documents' },
      { name: 'link_episode_document' },
      { name: 'unlink_episode_document' },
      { name: 'list_session_documents' },
      { name: 'link_session_document' },
      { name: 'unlink_session_document' },
      { name: 'match_episode_documents' },
    ],
  },
  {
    id: 'koofr',
    tools: [
      { name: 'list_koofr_places' },
      { name: 'list_koofr_folder' },
      { name: 'search_koofr' },
      { name: 'read_koofr_file' },
      { name: 'upload_koofr_file' },
      { name: 'create_koofr_folder' },
      { name: 'move_koofr_file' },
      { name: 'list_episode_koofr_folders' },
      { name: 'link_episode_koofr_folder' },
      { name: 'unlink_episode_koofr_folder' },
      { name: 'list_session_koofr_folders' },
      { name: 'link_session_koofr_folder' },
      { name: 'unlink_session_koofr_folder' },
    ],
  },
  {
    id: 'dropbox',
    tools: [
      { name: 'list_dropbox_places' },
      { name: 'list_dropbox_folder' },
      { name: 'search_dropbox' },
      { name: 'read_dropbox_file' },
      { name: 'view_dropbox_image' },
      { name: 'upload_dropbox_file' },
      { name: 'create_dropbox_folder' },
      { name: 'move_dropbox_file' },
      { name: 'list_episode_dropbox_folders' },
      { name: 'link_episode_dropbox_folder' },
      { name: 'unlink_episode_dropbox_folder' },
      { name: 'list_session_dropbox_folders' },
      { name: 'link_session_dropbox_folder' },
      { name: 'unlink_session_dropbox_folder' },
    ],
  },
  {
    id: 'patreon',
    tools: [
      { name: 'list_patreon_tiers' },
      { name: 'list_patreon_members' },
      { name: 'list_patreon_posts' },
      { name: 'list_patreon_drafts' },
      { name: 'get_patreon_post' },
      { name: 'get_patreon_post_media' },
      { name: 'create_patreon_draft' },
      { name: 'update_patreon_post' },
      { name: 'set_patreon_post_media' },
      { name: 'set_patreon_post_image' },
      { name: 'prepare_patreon_post' },
      { name: 'delete_patreon_post' },
      { name: 'get_patreon_job' },
    ],
  },
  {
    id: 'pictures',
    tools: [
      { name: 'set_episode_image' },
      { name: 'remove_episode_image' },
      { name: 'list_episode_images' },
      { name: 'set_person_avatar' },
      { name: 'remove_person_avatar' },
      { name: 'get_person_avatar' },
    ],
  },
  {
    id: 'media',
    tools: [
      { name: 'list_episode_media' },
      { name: 'update_episode_media' },
      { name: 'remove_episode_media' },
    ],
  },
  {
    id: 'transcripts',
    tools: [
      { name: 'transcribe_episode' },
      { name: 'list_transcriptions' },
      { name: 'get_transcription' },
      { name: 'cancel_transcription' },
      { name: 'get_episode_transcript' },
      { name: 'remove_transcript' },
    ],
  },
  {
    id: 'history',
    tools: [
      { name: 'get_history' },
      { name: 'list_recent_changes' },
      { name: 'list_deleted' },
      { name: 'restore_link' },
    ],
  },
]

/** The groups and their tools, as the page's language says what they are and do */
export function toolGroups(t: Translate): ToolGroup[] {
  return TOOL_GROUPS.map((group) => ({
    id: group.id,
    title: t(`tools.groups.${group.id}.title`),
    about: t(`tools.groups.${group.id}.about`),
    tools: group.tools.map(({ name }) => ({ name, description: t(`tools.descriptions.${name}`) })),
  }))
}
