// MetaOS's MCP tools, for the Usage page, by what they work on. A tool that's added, renamed, removed or does
// something else changes here too (CLAUDE.md); UsageToolsTest checks the names, but not the descriptions.

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

export const TOOL_GROUPS: ToolGroup[] = [
  {
    id: 'search',
    title: 'Search',
    about: 'episodes, ideas, people and recording sessions at once',
    tools: [
      { name: 'search', description: 'Find episodes, people and sessions by a code, a name or words in their titles and summaries; a code finds its episode on its own' },
    ],
  },
  {
    id: 'episodes',
    title: 'Episodes',
    about: 'from idea to release',
    tools: [
      { name: 'list_episodes', description: 'List episodes, latest first, optionally only those with a given status' },
      { name: 'get_episode', description: 'An episode by its code or ID, with its summaries' },
      { name: 'create_episode', description: 'Add an episode; it starts as an idea, which can do without a code, recommended by you unless you name someone else' },
      { name: 'set_episode_recommended_by', description: 'Set or clear who recommended an episode' },
      { name: 'update_episode', description: "Change an episode's code, title, alternative titles for A/B tests, summaries or dates" },
      { name: 'set_episode_status', description: 'Move an episode through its lifecycle, from idea to published, one step at a time' },
      { name: 'set_episode_link', description: 'Set or clear its YouTube, Spotify or Apple Podcasts link, or one of its four on Patreon: the full episode or its bonus, as video or audio only' },
      { name: 'delete_episode', description: 'Delete an episode; it can be restored' },
      { name: 'restore_episode', description: 'Bring back a deleted episode, with its people, links, session and show; under a new code if its own is taken' },
    ],
  },
  {
    id: 'people',
    title: 'People',
    about: 'hosts, guests, producers and you',
    tools: [
      { name: 'list_people', description: 'List all people' },
      { name: 'create_person', description: 'Add a person' },
      { name: 'set_person_email', description: "Set or clear a person's email; accounts that sign in with it are linked to them" },
      { name: 'set_person_bio', description: 'Set or clear the line about who a person is, e.g. their role and company' },
      { name: 'set_person_link', description: 'Set or clear their homepage, LinkedIn, Twitter/X, Instagram or Facebook' },
      { name: 'delete_person', description: 'Delete a person; they drop off their episodes, and those they recommended, until they are restored' },
      { name: 'restore_person', description: 'Bring back a deleted person, with their roles and the episodes they recommended; with another email, or none, if theirs is taken' },
      { name: 'list_episode_people', description: 'The people on an episode and their roles' },
      { name: 'assign_person_to_episode', description: 'Give a person a role on an episode: host, co-host, guest, producer, consultant, designer, or candidate for someone who could be its guest' },
      { name: 'remove_person_from_episode', description: 'Take a person off an episode: one of their roles, or all of them' },
      { name: 'confirm_guest', description: "Make a candidate an episode's guest, once they've said yes" },
      { name: 'list_person_episodes', description: 'The episodes a person is on, and their roles' },
      { name: 'whoami', description: "Who you're signed in as: your account and the person it's linked to" },
      { name: 'my_episodes', description: "The episodes you're on, and your roles" },
      { name: 'list_accounts', description: 'The accounts MetaOS has seen and the people they are, or only those not linked to anyone' },
      { name: 'link_account', description: 'Link an account to the person it is, so its comments and changes go by their name, past ones too; only Oto may' },
      { name: 'unlink_account', description: 'Unlink an account from its person, e.g. one linked to the wrong person; only Oto may' },
    ],
  },
  {
    id: 'sessions',
    title: 'Recording sessions',
    about: 'days on which episodes are recorded',
    tools: [
      { name: 'list_sessions', description: 'List all sessions, newest first' },
      { name: 'create_session', description: 'Add a session: its date, name, location and address' },
      { name: 'update_session', description: "Change a session's date, name, location or address" },
      { name: 'delete_session', description: 'Delete a session; its episodes are kept' },
      { name: 'restore_session', description: 'Bring back a deleted session, with its episodes' },
      { name: 'list_session_episodes', description: 'The episodes of a session' },
      { name: 'set_episode_session', description: 'Move an episode into a session, or out of one' },
    ],
  },
  {
    id: 'discussion',
    title: 'Discussion',
    about: 'comments on episodes, ideas and recording sessions, in Markdown, as on GitHub',
    tools: [
      { name: 'list_episode_comments', description: "An episode's or idea's comments, oldest first, with who wrote them and when" },
      { name: 'list_session_comments', description: "A recording session's comments, oldest first" },
      { name: 'comment_on_episode', description: 'Comment on an episode or idea as you, in Markdown' },
      { name: 'comment_on_session', description: 'Comment on a recording session as you, in Markdown' },
      { name: 'edit_comment', description: 'Change the text of one of your comments; it shows as edited' },
      { name: 'delete_comment', description: 'Delete one of your comments; the history keeps that it was there' },
    ],
  },
  {
    id: 'shows',
    title: 'Shows',
    about: 'series of episodes, such as Borderless',
    tools: [
      { name: 'list_shows', description: 'List all shows' },
      { name: 'get_show', description: 'A show by its name' },
      { name: 'create_show', description: 'Add a show' },
      { name: 'update_show', description: 'Rename a show or change its color' },
      { name: 'delete_show', description: 'Delete a show; its episodes are kept' },
      { name: 'restore_show', description: 'Bring back a deleted show, with its episodes; under a new name if its own is taken' },
      { name: 'list_show_episodes', description: 'The episodes of a show' },
      { name: 'set_episode_show', description: 'Move an episode into a show, or out of one' },
    ],
  },
  {
    id: 'google-drive',
    title: 'Google Drive',
    about: "Ogrodje's folder",
    tools: [
      { name: 'list_drive_folder', description: 'What is in a folder' },
      { name: 'search_documents', description: 'Search Google Docs by the words in their title or text' },
      { name: 'read_document', description: 'Read a Google Doc, as Markdown' },
      { name: 'list_episode_documents', description: 'The Google Docs of an episode and of its recording session' },
      { name: 'link_episode_document', description: 'Link a Google Doc to an episode, e.g. its preparation doc' },
      { name: 'unlink_episode_document', description: 'Unlink a Google Doc from an episode; the doc stays in Drive' },
      { name: 'list_session_documents', description: 'The Google Docs of a recording session' },
      { name: 'link_session_document', description: 'Link a Google Doc to a recording session, e.g. its recording day plan' },
      { name: 'unlink_session_document', description: 'Unlink a Google Doc from a recording session; the doc stays in Drive' },
      { name: 'match_episode_documents', description: 'Link the docs in a folder to episodes by the code in their titles; only reports, unless told to link' },
    ],
  },
  {
    id: 'koofr',
    title: 'Koofr',
    about: "the folders shared with Ogrodje's bot",
    tools: [
      { name: 'list_koofr_places', description: "The folders shared with Ogrodje's bot, and whether it may change them" },
      { name: 'list_koofr_folder', description: 'What is in a folder' },
      { name: 'search_koofr', description: 'Search for files and folders by name' },
      { name: 'read_koofr_file', description: 'Read a text file: txt, md, csv, json, srt and the like' },
      { name: 'upload_koofr_file', description: 'Upload a file; one of the same name is only replaced when asked' },
      { name: 'create_koofr_folder', description: 'Create a folder' },
      { name: 'move_koofr_file', description: 'Move or rename a file or folder; what it is linked to stays linked' },
      { name: 'list_episode_koofr_folders', description: 'The Koofr folders of an episode and of its recording session' },
      { name: 'link_episode_koofr_folder', description: 'Link a Koofr folder to an episode, e.g. the one with its recordings' },
      { name: 'unlink_episode_koofr_folder', description: 'Unlink a Koofr folder from an episode; the folder stays in Koofr' },
      { name: 'list_session_koofr_folders', description: 'The Koofr folders of a recording session' },
      { name: 'link_session_koofr_folder', description: 'Link a Koofr folder to a recording session' },
      { name: 'unlink_session_koofr_folder', description: 'Unlink a Koofr folder from a recording session; the folder stays in Koofr' },
    ],
  },
  {
    id: 'dropbox',
    title: 'Dropbox',
    about: "the folders shared with Ogrodje's bot, like Artwork",
    tools: [
      { name: 'list_dropbox_places', description: "The folders shared with Ogrodje's bot, and whether it may change them" },
      { name: 'list_dropbox_folder', description: 'What is in a folder' },
      { name: 'search_dropbox', description: 'Search for files and folders by name' },
      { name: 'read_dropbox_file', description: 'Read a text file: txt, md, csv, json, srt and the like' },
      { name: 'view_dropbox_image', description: 'Look at a picture, e.g. artwork for an episode' },
      { name: 'upload_dropbox_file', description: 'Upload a file; one of the same name is only replaced when asked' },
      { name: 'create_dropbox_folder', description: 'Create a folder' },
      { name: 'move_dropbox_file', description: 'Move or rename a file or folder' },
      { name: 'list_episode_dropbox_folders', description: 'The Dropbox folders of an episode and of its recording session' },
      { name: 'link_episode_dropbox_folder', description: 'Link a Dropbox folder to an episode, e.g. the one with its artwork' },
      { name: 'unlink_episode_dropbox_folder', description: 'Unlink a Dropbox folder from an episode; the folder stays in Dropbox' },
      { name: 'list_session_dropbox_folders', description: 'The Dropbox folders of a recording session' },
      { name: 'link_session_dropbox_folder', description: 'Link a Dropbox folder to a recording session' },
      { name: 'unlink_session_dropbox_folder', description: 'Unlink a Dropbox folder from a recording session; the folder stays in Dropbox' },
    ],
  },
  {
    id: 'patreon',
    title: 'Patreon',
    about: "Ogrodje's Patreon: drafts, an episode's audio or video, and publishing",
    tools: [
      { name: 'list_patreon_tiers', description: 'The tiers, cheapest first, with their prices and patrons' },
      { name: 'list_patreon_members', description: 'The members, patrons first: their status, tiers, pledge, what they gave in all and their last charge' },
      { name: 'list_patreon_posts', description: 'The published posts, newest first, or those with words in their titles' },
      { name: 'get_patreon_post', description: 'A post, published or a draft, with its text and who may see it' },
      { name: 'create_patreon_draft', description: 'Make a draft post, for everyone, every patron or some tiers; only the team sees it' },
      { name: 'update_patreon_post', description: "Change a post's title, text or who may see it" },
      { name: 'set_patreon_post_media', description: "Upload an episode's audio or video from Dropbox or a URL to a draft, as a podcast or video post" },
      { name: 'publish_patreon_post', description: "Publish a draft, now or later, emailing patrons only if asked; it can become the episode's Patreon link" },
      { name: 'delete_patreon_post', description: "Delete a post, e.g. a draft that isn't needed" },
      { name: 'get_patreon_job', description: 'How a change in Patreon is going, e.g. an upload: done, failed and why, or waiting to try again' },
    ],
  },
  {
    id: 'pictures',
    title: 'Pictures',
    about: 'thumbnails, covers and avatars for everyone to see, at media.ogrodje.si',
    tools: [
      { name: 'set_episode_image', description: "Make a picture from Dropbox or Koofr an episode's thumbnail, thumbnail B or C (for its alternative titles) or web picture, 1920 × 1080, or its cover, 3000 × 3000" },
      { name: 'remove_episode_image', description: 'Take one of its pictures off an episode' },
      { name: 'list_episode_images', description: "An episode's thumbnails, web picture and cover, with their URLs" },
      { name: 'set_person_avatar', description: "Make a picture from Hygraph, Dropbox or Koofr a person's avatar" },
      { name: 'remove_person_avatar', description: "Take a person's avatar off them" },
      { name: 'get_person_avatar', description: "A person's avatar, with its URLs" },
    ],
  },
  {
    id: 'history',
    title: 'History',
    about: 'who changed what, and when, and bringing back what was deleted',
    tools: [
      { name: 'get_history', description: 'Every change to an episode, person, session or show, its discussion too, with when and by whom' },
      { name: 'list_recent_changes', description: 'The latest changes to everything' },
      { name: 'list_deleted', description: 'The deleted episodes, people, sessions and shows, with when and by whom, to restore' },
      { name: 'restore_link', description: 'Bring back a removed role, Google Doc, Koofr or Dropbox folder, from the change that removed it' },
    ],
  },
]
