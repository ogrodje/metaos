// Where an episode went out once it was released, as MetaOS's GraphQL API has it (Distributions.kt): its own links,
// which follow the episode's (set_episode_link), and the posts that promote it, each with its page's preview

import type { IconDefinition } from '@fortawesome/fontawesome-svg-core'
import {
  faBluesky, faDiscord, faFacebook, faInstagram, faLinkedin, faMastodon, faPatreon, faReddit, faSpotify, faThreads,
  faXTwitter, faYoutube,
} from '@fortawesome/free-brands-svg-icons'
import { faEnvelope, faGlobe, faNewspaper, faPodcast } from '@fortawesome/free-solid-svg-icons'
import type { Translate } from '@/i18n'
import { type Image, IMAGE_FIELDS } from '@/images'

/** Where it is, by its URL's host, else as someone said (Channel in the schema) */
export type Channel =
  | 'YOUTUBE' | 'PATREON' | 'SPOTIFY' | 'APPLE_PODCASTS' | 'DISCORD' | 'LINKEDIN' | 'FACEBOOK' | 'INSTAGRAM' | 'REDDIT'
  | 'X' | 'BLUESKY' | 'MASTODON' | 'THREADS' | 'MONITOR' | 'NEWSLETTER' | 'OTHER'

/** The episode's links (Platform in the schema), each of which is an entry too */
export type LinkPlatform =
  | 'YOUTUBE' | 'SPOTIFY' | 'APPLE_PODCASTS' | 'PATREON' | 'PATREON_AUDIO' | 'PATREON_BONUS' | 'PATREON_BONUS_AUDIO'

export type PreviewStatus = 'PENDING' | 'OK' | 'UNAVAILABLE'

export interface DistributionPreview {
  status: PreviewStatus
  title: string | null
  description: string | null
  // e.g. LinkedIn, or the page's host
  site: string | null
  publishedAt: string | null
  image: Image | null
  // Why there's none, e.g. a page that needs signing in
  error: string | null
  fetchedAt: string | null
}

export interface Distribution {
  id: string
  // The episode's link this is; null for a post
  platform: LinkPlatform | null
  channel: Channel
  url: string
  // What someone called it; the preview's title otherwise
  title: string | null
  note: string | null
  // When it went out, as someone said; the preview's otherwise
  postedAt: string | null
  addedBy: string | null
  createdAt: string
  preview: DistributionPreview
}

export const DISTRIBUTION_ENTRY_FIELDS = `id platform channel url title note postedAt addedBy createdAt
  preview { status title description site publishedAt error fetchedAt image { ${IMAGE_FIELDS} } }`

/** An episode's distribution, for a query */
export const DISTRIBUTION_FIELDS = `distributions { ${DISTRIBUTION_ENTRY_FIELDS} }`

/**
 * Each channel with its logo in its colours, in the order they're chosen from, and its brand's name, which is the same
 * in every language; those that aren't brands are named by their messages (channelName)
 */
export const CHANNELS: Record<Channel, { brand?: string, icon: IconDefinition, color: string }> = {
  YOUTUBE: { brand: 'YouTube', icon: faYoutube, color: '#FF0000' },
  PATREON: { brand: 'Patreon', icon: faPatreon, color: '#FF424D' },
  SPOTIFY: { brand: 'Spotify', icon: faSpotify, color: '#1DB954' },
  APPLE_PODCASTS: { brand: 'Apple Podcasts', icon: faPodcast, color: '#9933CC' },
  DISCORD: { brand: 'Discord', icon: faDiscord, color: '#5865F2' },
  LINKEDIN: { brand: 'LinkedIn', icon: faLinkedin, color: '#0A66C2' },
  FACEBOOK: { brand: 'Facebook', icon: faFacebook, color: '#0866FF' },
  INSTAGRAM: { brand: 'Instagram', icon: faInstagram, color: '#E4405F' },
  REDDIT: { brand: 'Reddit', icon: faReddit, color: '#FF4500' },
  X: { brand: 'X', icon: faXTwitter, color: '#000000' },
  BLUESKY: { brand: 'Bluesky', icon: faBluesky, color: '#1185FE' },
  MASTODON: { brand: 'Mastodon', icon: faMastodon, color: '#6364FF' },
  THREADS: { brand: 'Threads', icon: faThreads, color: '#000000' },
  MONITOR: { brand: 'Monitor', icon: faNewspaper, color: 'var(--secondary)' },
  NEWSLETTER: { icon: faEnvelope, color: 'var(--secondary)' },
  OTHER: { icon: faGlobe, color: 'var(--secondary)' },
}

/** A channel's name in the page's language: its brand's, else its message's, e.g. Newsletter */
export function channelName(channel: Channel, t: Translate) {
  return CHANNELS[channel].brand ?? t(`distribution.channels.${channel}`)
}

/** What each of the episode's links is, as its row says it, e.g. Patreon, the bonus as audio */
export function linkName(platform: LinkPlatform, t: Translate) {
  return t(`distribution.links.${platform}`)
}

// The hosts MetaOS tells channels by, as Channel in Distributions.kt has them
const HOSTS: [Channel, string[]][] = [
  ['YOUTUBE', ['youtube.com', 'youtu.be']],
  ['PATREON', ['patreon.com']],
  ['SPOTIFY', ['spotify.com', 'spotify.link']],
  ['APPLE_PODCASTS', ['podcasts.apple.com']],
  ['DISCORD', ['discord.com', 'discord.gg', 'discordapp.com']],
  ['LINKEDIN', ['linkedin.com', 'lnkd.in']],
  ['FACEBOOK', ['facebook.com', 'fb.com', 'fb.watch']],
  ['INSTAGRAM', ['instagram.com']],
  ['REDDIT', ['reddit.com', 'redd.it']],
  ['X', ['x.com', 'twitter.com']],
  ['BLUESKY', ['bsky.app']],
  ['THREADS', ['threads.net', 'threads.com']],
  ['MONITOR', ['monitor.si']],
  ['NEWSLETTER', ['substack.com']],
]

/** The channel a URL is on, as MetaOS will tell it, e.g. to say so while it's typed; null for one that isn't a URL */
export function channelOf(url: string): Channel | null {
  let host: string
  try {
    host = new URL(url.trim()).hostname.toLowerCase()
  } catch {
    return null
  }
  return HOSTS.find(([, hosts]) => hosts.some((h) => host === h || host.endsWith(`.${h}`)))?.[0] ?? 'OTHER'
}

/** What an entry is called: as someone named it, else as its page says, else by its URL's host and path */
export function distributionTitle(entry: Pick<Distribution, 'title' | 'preview' | 'url'>) {
  if (entry.title ?? entry.preview.title) return (entry.title ?? entry.preview.title)!
  try {
    const url = new URL(entry.url)
    return `${url.hostname.replace(/^www\./, '')}${url.pathname === '/' ? '' : url.pathname}`
  } catch {
    return entry.url
  }
}

/**
 * When it went out, as someone said, else as its page says; else, for one of the episode's links, when the episode
 * aired ([airedDate]); else when it was added
 */
export function distributedAt(entry: Pick<Distribution, 'postedAt' | 'preview' | 'createdAt' | 'platform'>, airedDate?: string | null) {
  return entry.postedAt ?? entry.preview.publishedAt ?? (entry.platform && airedDate) ?? entry.createdAt
}

/** The folded part's line: how many posts, and where, e.g. "3 posts: LinkedIn ×2, Discord", after its links */
export function distributionLine(entries: Distribution[], t: Translate): string {
  const posts = entries.filter((entry) => !entry.platform)
  const links = new Set(entries.filter((entry) => entry.platform).map((entry) => channelName(entry.channel, t)))
  const parts: string[] = []
  if (links.size) parts.push(t('distribution.line.on', { where: [...links].join(', ') }))
  if (posts.length) {
    const counts = new Map<string, number>()
    for (const post of posts) {
      const name = channelName(post.channel, t)
      counts.set(name, (counts.get(name) ?? 0) + 1)
    }
    const where = [...counts].sort((a, b) => b[1] - a[1]).map(([label, n]) => (n > 1 ? `${label} ×${n}` : label))
    parts.push(t('distribution.line.posts', { n: posts.length, where: where.join(', ') }, posts.length))
  } else {
    parts.push(t('distribution.line.none'))
  }
  return parts.join(' · ')
}
