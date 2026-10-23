// People as MetaOS's GraphQL API has them (src/main/resources/graphql/schema.graphqls)

import type { Account } from '@/accounts'
import { type Episode, type Role, roleName } from '@/episodes'
import { type Image, IMAGE_FIELDS } from '@/images'

/** An episode a person is on, with their roles in it */
export interface PersonEpisode {
  episode: Pick<Episode, 'id' | 'code' | 'title'>
  roles: Role[]
}

export interface Person {
  id: string
  name: string
  // A line about who they are, e.g. their role and company
  bio: string | null
  homepageUrl: string | null
  linkedinUrl: string | null
  twitterUrl: string | null
  instagramUrl: string | null
  facebookUrl: string | null
  avatar: Image | null
  // The latest first; for anyone not signed in only the released ones
  episodes: PersonEpisode[]
  // The accounts they sign in with, when a query asks for them (not PERSON_FIELDS); none without a signed-in user
  accounts?: Account[]
}

/** A person's page */
export function personPath(person: Pick<Person, 'id'>) {
  return `/people/${person.id}`
}

/** The fields of a Person, for a query */
export const PERSON_FIELDS = `
  id name bio homepageUrl linkedinUrl twitterUrl instagramUrl facebookUrl avatar { ${IMAGE_FIELDS} }
  episodes { episode { id code title } roles }
`

const networks = {
  Website: 'homepageUrl',
  LinkedIn: 'linkedinUrl',
  X: 'twitterUrl',
  Instagram: 'instagramUrl',
  Facebook: 'facebookUrl',
} as const

export type Network = keyof typeof networks

/** Where a person is online: their website, then their social profiles */
export function socialLinks(person: Pick<Person, (typeof networks)[Network]>) {
  return (Object.keys(networks) as Network[]).flatMap((network) => {
    const url = person[networks[network]]
    return url ? [{ network, url }] : []
  })
}

// In the order an episode's people are listed
const roles: Role[] = ['HOST', 'CO_HOST', 'GUEST', 'PRODUCER', 'CONSULTANT', 'DESIGNER', 'CANDIDATE']

/** What a person has been on any of their episodes, hosts first, e.g. Host, co-host, guest */
export function rolesOf(person: Pick<Person, 'episodes'>) {
  const theirs = new Set(person.episodes.flatMap((episode) => episode.roles))
  const names = roles.filter((role) => theirs.has(role)).map(roleName).join(', ')
  return names.charAt(0).toUpperCase() + names.slice(1)
}
