import MarkdownIt from 'markdown-it'

// Raw HTML stays text (html: false) and links go only to safe protocols (markdown-it's own check), so no summary or
// comment can run a script here, AI-written ones included. Single line breaks stay breaks, as the summaries are written.
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })
// Bare domains (ogrodje.si) are links too, as before markdown-it 15 turned that off
markdown.linkify.set({ fuzzyLink: true })

// Where pictures may load from. A picture loads as soon as the page shows it, so its host learns who looked, and its URL
// carries whatever the writer put in it, e.g. text an agent was steered into copying there. A picture from anywhere
// else is shown as a link, which waits for a click.
//
// - media.ogrodje.si: MetaOS's own public bucket, nothing there that isn't public already.
// - Dropbox and Koofr shared links: anyone can make one, an outsider too, so a link's owner may see that it was opened
//   (Dropbox counts views of shared links) and Dropbox and Koofr see who opened it. What they don't give the owner is
//   the request itself, so a URL can't carry text out the way one to the writer's own server can. Drop these two if
//   that's still too much.
//
// MetaOS's own /dropbox/thumbnail and /koofr/thumbnail aren't here: they need the sign-in token, which an <img> can't
// send (graphql.ts fetchObjectUrl).
const IMAGE_HOSTS = new Set([
  'media.ogrodje.si',
  // Dropbox: dl.dropboxusercontent.com for files, www.dropbox.com for shared links with ?raw=1
  'dl.dropboxusercontent.com',
  'www.dropbox.com',
  // Koofr's shared links
  'app.koofr.net',
])

/** Whether a picture at [src] may load by itself: https, and one of [IMAGE_HOSTS] exactly */
export function imageAllowed(src: string): boolean {
  try {
    const url = new URL(src)
    return url.protocol === 'https:' && IMAGE_HOSTS.has(url.hostname) && !url.username && !url.password
  } catch {
    // Relative, or not a URL at all
    return false
  }
}

// Links lead out of MetaOS, so they open in a tab of their own
const openInTab = 'target="_blank" rel="noopener noreferrer"'
const linkOpen = markdown.renderer.rules.link_open ?? ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index]?.attrSet('target', '_blank')
  tokens[index]?.attrSet('rel', 'noopener noreferrer')
  return linkOpen(tokens, index, options, env, self)
}

// A picture from elsewhere: a link to it, named by its alt text
const image = markdown.renderer.rules.image!
markdown.renderer.rules.image = (tokens, index, options, env, self) => {
  const token = tokens[index]!
  const src = String(token.attrGet('src') ?? '')
  if (imageAllowed(src)) return image(tokens, index, options, env, self)
  const text = self.renderInlineAsText(token.children ?? [], options, env) || src
  return `<a href="${markdown.utils.escapeHtml(src)}" ${openInTab}>${markdown.utils.escapeHtml(text)}</a>`
}

export const renderMarkdown = (source: string) => markdown.render(source)
