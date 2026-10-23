import MarkdownIt from 'markdown-it'
import { useHead, useRuntimeConfig, useSeoMeta } from '#imports'
import { type Episode, episodePath, links } from '@/episodes'
import { type Image, src } from '@/images'
import { type Person, personPath, socialLinks } from '@/people'

// What search engines and link previews (Discord, Slack, X, ...) show for a page, beyond its title (title.ts): what it's
// about in a sentence, its picture, and what it is, as schema.org's structured data. All in the page the server renders,
// as most of them don't run JavaScript.

/** A page's description, picture and structured data; each may be missing, e.g. while it loads */
export interface PageMeta {
  // Its one address, when it has another than the one it was opened at, e.g. an episode's by its code rather than ID
  path?: string
  description?: string | null
  image?: Image | null
  structured?: Record<string, unknown> | null
}

// How long a description may be: about what search results show of it
const DESCRIPTION_LENGTH = 200

// Links' text, without their addresses, and no emphasis
const markdown = new MarkdownIt({ html: false })

/** [source], Markdown, as plain text: its words, e.g. a summary's for a description */
export function plainText(source: string) {
  return markdown.parse(source, {})
    .flatMap((token) => token.children ?? [])
    .map((token) => (token.type === 'softbreak' || token.type === 'hardbreak' ? ' ' : token.content))
    .join('')
    .replace(/\s+/g, ' ')
    .trim()
}

/** At most [length] characters of [text], cut after a word */
export function shorten(text: string, length = DESCRIPTION_LENGTH) {
  if (text.length <= length) return text
  const cut = text.slice(0, length - 1)
  return `${cut.slice(0, cut.lastIndexOf(' ') > 0 ? cut.lastIndexOf(' ') : cut.length).replace(/[\s,;:.]+$/, '')}…`
}

// A picture for link previews: a copy about 1200 px wide, which is what they show, rather than the original
function previewOf(image: Image) {
  const url = src(image, 1200)
  const width = image.sizes.find((size) => size.url === url)?.width ?? image.width
  return { url, width, height: Math.round((width * image.height) / image.width) }
}

// In a <script>, where "</script>" in a title mustn't end it
function json(data: Record<string, unknown>) {
  return JSON.stringify(data).replace(/</g, '\\u003c')
}

/** The site's public address of [path], e.g. for structured data */
export function useSiteUrl() {
  const { siteUrl } = useRuntimeConfig().public
  return (path: string) => siteUrl + path
}

/**
 * Says what the page is about to search engines and link previews, from what it shows once it's loaded. Its title is
 * usePageTitle's, and its address the path's (App.vue), unless it has another.
 */
export function usePageMeta(meta: () => PageMeta) {
  const url = useSiteUrl()
  const preview = () => {
    const image = meta().image
    return image ? previewOf(image) : undefined
  }
  useSeoMeta({
    description: () => meta().description ?? undefined,
    ogDescription: () => meta().description ?? undefined,
    ogImage: () => preview()?.url,
    ogImageWidth: () => preview()?.width,
    ogImageHeight: () => preview()?.height,
    twitterCard: () => (meta().image ? 'summary_large_image' : 'summary'),
  })
  useHead({
    link: () => {
      const path = meta().path
      return path ? [{ rel: 'canonical', href: url(path) }] : []
    },
    meta: () => {
      const path = meta().path
      return path ? [{ property: 'og:url', content: url(path) }] : []
    },
    script: () => {
      const structured = meta().structured
      return structured ? [{ key: 'structured-data', type: 'application/ld+json', innerHTML: json(structured) }] : []
    },
  })
}

// The podcast the episodes are of, unless they're of another show
const SERIES = { '@type': 'PodcastSeries', name: 'Ogrodje', url: 'https://ogrodje.si' }

// Who's on an episode, as schema.org's actors: those who talk, not those behind it
const ON_AIR = new Set(['HOST', 'CO_HOST', 'GUEST'])

/** A released episode as schema.org's PodcastEpisode, e.g. S05E08 is the 8th of season 5 */
export function episodeData(
  episode: Episode & { summary?: string | null, thumbnail?: Image | null },
  url: (path: string) => string,
) {
  const [, season, number] = episode.code?.match(/^S(\d+)E(\d+)$/i) ?? []
  const about = episode.tinySummary ?? episode.summary
  return {
    '@context': 'https://schema.org',
    '@type': 'PodcastEpisode',
    name: episode.title,
    url: url(episodePath(episode)),
    ...(about ? { description: shorten(plainText(about)) } : {}),
    ...(episode.airedDate ? { datePublished: episode.airedDate } : {}),
    ...(episode.thumbnail ? { image: previewOf(episode.thumbnail).url } : {}),
    ...(number ? { episodeNumber: String(Number(number)) } : {}),
    ...(season ? { partOfSeason: { '@type': 'PodcastSeason', seasonNumber: Number(season) } } : {}),
    partOfSeries: episode.show && episode.show !== SERIES.name ? { '@type': 'PodcastSeries', name: episode.show } : SERIES,
    inLanguage: 'sl',
    actor: episode.people.filter((person) => person.roles.some((role) => ON_AIR.has(role))).map((person) => ({
      '@type': 'Person',
      name: person.name,
      url: url(personPath({ id: person.personId })),
    })),
    sameAs: links(episode).map((link) => link.url),
  }
}

/** A person as schema.org has them, with where they're online */
export function personData(person: Omit<Person, 'episodes'>, url: (path: string) => string) {
  return {
    '@context': 'https://schema.org',
    '@type': 'Person',
    name: person.name,
    url: url(personPath(person)),
    ...(person.bio ? { description: person.bio } : {}),
    ...(person.avatar ? { image: src(person.avatar, 640) } : {}),
    sameAs: socialLinks(person).map((link) => link.url),
  }
}
