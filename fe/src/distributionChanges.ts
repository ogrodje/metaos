import { type Channel, type Distribution, DISTRIBUTION_ENTRY_FIELDS } from '@/distribution'
import { graphql } from '@/graphql'

// Changes to where an episode went out (distribution.ts), through MetaOS's GraphQL API, with metaos:write

/** Logs a post of an episode, by its ID, answering with the entry; a URL it has already is that entry */
export async function addDistribution(ref: string, url: string, fields: { channel?: Channel | null, postedAt?: string | null, note?: string | null }) {
  return (await graphql<{ addDistribution: Distribution }>(
    `mutation ($ref: String!, $url: String!, $channel: Channel, $postedAt: String, $note: String) {
      addDistribution(ref: $ref, url: $url, channel: $channel, postedAt: $postedAt, note: $note) { ${DISTRIBUTION_ENTRY_FIELDS} }
    }`,
    { ref, url, channel: fields.channel ?? null, postedAt: fields.postedAt || null, note: fields.note || null },
  )).addDistribution
}

/** Changes an entry: what's left out stays, an empty note, title or date clears it */
export async function updateDistribution(
  id: string, fields: { url?: string, channel?: Channel, postedAt?: string, note?: string, title?: string },
) {
  return (await graphql<{ updateDistribution: Distribution }>(
    `mutation ($id: ID!, $url: String, $channel: Channel, $postedAt: String, $note: String, $title: String) {
      updateDistribution(id: $id, url: $url, channel: $channel, postedAt: $postedAt, note: $note, title: $title) { ${DISTRIBUTION_ENTRY_FIELDS} }
    }`,
    { id, ...fields },
  )).updateDistribution
}

/** Takes an entry off its episode; one of its links is cleared on the episode too */
export async function removeDistribution(id: string) {
  await graphql<{ removeDistribution: string }>('mutation ($id: ID!) { removeDistribution(id: $id) }', { id })
}

/** Fetches an entry's preview again */
export async function refreshPreview(id: string) {
  return (await graphql<{ refreshDistributionPreview: Distribution }>(
    `mutation ($id: ID!) { refreshDistributionPreview(id: $id) { ${DISTRIBUTION_ENTRY_FIELDS} } }`, { id },
  )).refreshDistributionPreview
}
