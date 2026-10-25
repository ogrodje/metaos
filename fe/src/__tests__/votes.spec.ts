import { describe, expect, it } from 'vitest'
import { byVotes, scoreText, toggled, type Votes, votersText } from '@/votes'

function votes(fields: Partial<Votes> = {}): Votes {
  return { score: 0, up: 0, down: 0, mine: 'NONE', voters: [], ...fields }
}

describe('toggled', () => {
  it('casts the vote clicked, or takes it back when it is yours already', () => {
    expect(toggled('NONE', 'UP')).toBe('UP')
    expect(toggled('DOWN', 'UP')).toBe('UP')
    expect(toggled('UP', 'UP')).toBe('NONE')
    expect(toggled('DOWN', 'DOWN')).toBe('NONE')
  })
})

describe('scoreText', () => {
  it('signs a score above 0', () => {
    expect([scoreText(4), scoreText(0), scoreText(-1)]).toEqual(['+4', '0', '-1'])
  })
})

describe('votersText', () => {
  it('names who voted each way', () => {
    const shown = votes({
      voters: [
        { name: 'Oto', username: 'oto', person: null, vote: 'UP' },
        { name: 'Maja', username: 'maja', person: null, vote: 'UP' },
        { name: 'Luka', username: 'luka', person: null, vote: 'DOWN' },
      ],
    })
    expect(votersText(shown)).toBe('▲ 2 Oto, Maja · ▼ 1 Luka')
    expect(votersText(votes())).toBe('No votes yet')
  })
})

describe('byVotes', () => {
  it('puts the most wanted first, then those with more ups, and keeps the order of the rest', () => {
    const ideas = [
      { id: 'a', votes: votes() },
      { id: 'b', votes: votes({ score: 2, up: 2 }) },
      { id: 'c', votes: votes({ score: -1, down: 1 }) },
      { id: 'd', votes: votes({ score: 0, up: 1, down: 1 }) },
      { id: 'e', votes: votes() },
    ]
    expect(byVotes(ideas).map((idea) => idea.id)).toEqual(['b', 'd', 'a', 'e', 'c'])
  })
})
